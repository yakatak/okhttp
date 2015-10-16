/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.ws;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketListener;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.Executor;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import static com.squareup.okhttp.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static com.squareup.okhttp.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static com.squareup.okhttp.internal.ws.WebSocketReader.FrameCallback;

public abstract class RealWebSocket implements WebSocket {
  /** A close code which indicates that the peer encountered a protocol exception. */
  private static final int CLOSE_PROTOCOL_EXCEPTION = 1002;

  private final WebSocketWriter writer;
  private final WebSocketReader reader;
  private final WebSocketListener listener;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private volatile boolean writerSentClose;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private volatile boolean readerSentClose;
  /** Lock required to negotiate closing the connection. */
  private final Object closeLock = new Object();

  public RealWebSocket(boolean isClient, BufferedSource source, BufferedSink sink, Random random,
      final Executor replyExecutor, final WebSocketListener listener, final String url) {
    this.listener = listener;

    writer = new WebSocketWriter(isClient, sink, random);
    reader = new WebSocketReader(isClient, source, new FrameCallback() {
      @Override public void onMessage(ResponseBody message) throws IOException {
        listener.onMessage(message);
      }

      @Override public void onPing(final Buffer buffer) {
        replyExecutor.execute(new NamedRunnable("OkHttp %s WebSocket Pong Reply", url) {
          @Override protected void execute() {
            try {
              writer.writePong(buffer);
            } catch (IOException ignored) {
            }
          }
        });
      }

      @Override public void onPong(Buffer buffer) {
        listener.onPong(buffer);
      }

      @Override public void onClose(final int code, final String reason) {
        final boolean writeCloseResponse;
        synchronized (closeLock) {
          readerSentClose = true;

          // If the writer has not indicated a desire to close we will write a close response.
          writeCloseResponse = !writerSentClose;
        }

        replyExecutor.execute(new NamedRunnable("OkHttp %s WebSocket Close Reply", url) {
          @Override protected void execute() {
            peerClose(code, reason, writeCloseResponse);
          }
        });
      }
    });
  }

  /**
   * Read a single message from the web socket and deliver it to the listener. This method should
   * be called in a loop with the return value indicating whether looping should continue.
   */
  public boolean readMessage() {
    try {
      reader.processNextFrame();
      return !readerSentClose;
    } catch (IOException e) {
      readerErrorClose(e);
      return false;
    }
  }

  @Override public void sendMessage(RequestBody message) throws IOException {
    if (message == null) throw new NullPointerException("message == null");
    if (writerSentClose) throw new IllegalStateException("closed");

    MediaType contentType = message.contentType();
    if (contentType == null) {
      throw new IllegalArgumentException(
          "Message content type was null. Must use WebSocket.TEXT or WebSocket.BINARY.");
    }
    String contentSubtype = contentType.subtype();

    int formatOpcode;
    if (WebSocket.TEXT.subtype().equals(contentSubtype)) {
      formatOpcode = OPCODE_TEXT;
    } else if (WebSocket.BINARY.subtype().equals(contentSubtype)) {
      formatOpcode = OPCODE_BINARY;
    } else {
      throw new IllegalArgumentException("Unknown message content type: "
          + contentType.type() + "/" + contentType.subtype() // Omit any implicitly added charset.
          + ". Must use WebSocket.TEXT or WebSocket.BINARY.");
    }

    BufferedSink sink = Okio.buffer(writer.newMessageSink(formatOpcode));
    try {
      message.writeTo(sink);
    } finally {
      sink.close();
    }
  }

  @Override public void sendPing(Buffer payload) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");
    writer.writePing(payload);
  }

  /** Send an unsolicited pong with the specified payload. */
  public void sendPong(Buffer payload) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");
    writer.writePong(payload);
  }

  @Override public void close(int code, String reason) throws IOException {
    if (writerSentClose) throw new IllegalStateException("closed");

    boolean performClose;
    synchronized (closeLock) {
      writerSentClose = true;

      // If the reader has also indicated a desire to close we will close the connection.
      performClose = readerSentClose;
    }

    writer.writeClose(code, reason);

    if (performClose) {
      close();
    }
  }

  /** Replies and closes this web socket when a close frame is read from the peer. */
  private void peerClose(int code, String reason, boolean writeCloseResponse) {
    if (writeCloseResponse) {
      try {
        writer.writeClose(code, reason);
      } catch (IOException ignored) {
      }
    }

    try {
      close();
    } catch (IOException ignored) {
    }

    listener.onClose(code, reason);
  }

  /** Called on the reader thread when an error occurs. */
  private void readerErrorClose(IOException e) {
    boolean canSendClose;
    synchronized (closeLock) {
      readerSentClose = true;

      // If the writer has not closed we may inform the server of the close.
      canSendClose = !writerSentClose;
    }

    // For protocol exceptions, try to inform the server of such.
    if (canSendClose && e instanceof ProtocolException) {
      try {
        writer.writeClose(CLOSE_PROTOCOL_EXCEPTION, null);
      } catch (IOException ignored) {
      }
    }

    try {
      close();
    } catch (IOException ignored) {
    }

    listener.onFailure(e, null);
  }
}
