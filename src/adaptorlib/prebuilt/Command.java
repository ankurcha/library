// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package adaptorlib.prebuilt;

import adaptorlib.IOHelper;

import java.io.*;

/**
 * Exec helper that allows easy handling of stdin, stdout, and stderr. Normally
 * you have to worry about deadlock when dealing with those streams (as
 * mentioned briefly in {@link Process}), so this class handles that for you.
 */
public class Command {
  private int returnCode;
  private byte[] stdout;
  private byte[] stderr;

  public Command() {}

  /**
   * Same as {@code exec(command, null, new byte[0])}.
   *
   */
  public int exec(String[] command) throws IOException,
         InterruptedException {
    return exec(command, null, new byte[0]);
  }

  /**
   * Same as {@code exec(command, workingDir, new byte[0])}.
   *
   * @see #exec(String[], File, byte[])
   */
  public int exec(String[] command, File workingDir) throws IOException,
         InterruptedException {
    return exec(command, workingDir, new byte[0]);
  }

  /**
   * Same as {@code exec(command, null, stdin)}.
   *
   * @see #exec(String[], File, byte[])
   */
  public int exec(String[] command, byte[] stdin) throws IOException,
         InterruptedException {
    return exec(command, null, stdin);
  }

  /**
   * Create process {@code command} starting in the {@code workingDir} and
   * providing {@code stdin} as input. This method blocks until the process
   * exits. Stdout and stderr are available after the method terminates via
   * {@link #getStdout} and {@link #getStderr}. Before using them, however, you
   * should generally make sure that the process exited with a return code of
   * zero, as other return codes typically indicate an error.
   *
   * @return Process return code
   * @throws IOException if creating process fails
   */
  public int exec(String[] command, File workingDir, byte[] stdin)
      throws IOException, InterruptedException {
    // Clear so that if the object is reused, and the second use has an
    // InterruptedException, they don't accidentally use the wrong data.
    stdout = null;
    stderr = null;

    Process proc = Runtime.getRuntime().exec(command, null, workingDir);
    Thread in, out, err;
    in = new Thread(new StreamCopyRunnable(new ByteArrayInputStream(stdin),
                                           proc.getOutputStream(), true));
    in.setDaemon(true);
    in.start();

    ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
    out = new Thread(new StreamCopyRunnable(proc.getInputStream(), outBuffer,
                                            true));
    out.setDaemon(true);
    out.start();

    ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
    err = new Thread(new StreamCopyRunnable(proc.getErrorStream(), errBuffer,
                                            true));
    err.setDaemon(true);
    err.start();

    try {
      in.join();
      out.join();
      err.join();

      returnCode = proc.waitFor();
    } catch (InterruptedException ex) {
      // Our threads should stop once the process closes.
      // This destroy() is quite rude to the subprocess, but there is not any
      // way to inform it to abort.
      proc.destroy();
      throw ex;
    }

    stdout = outBuffer.toByteArray();
    stderr = errBuffer.toByteArray();

    return returnCode;
  }

  public int getReturnCode() {
    return returnCode;
  }

  /**
   * Returns internal byte array without copying.
   */
  public byte[] getStdout() {
    return stdout;
  }

  /**
   * Returns internal byte array without copying.
   */
  public byte[] getStderr() {
    return stderr;
  }

  private static class StreamCopyRunnable implements Runnable {
    private InputStream is;
    private OutputStream os;
    private boolean autoClose;

    public StreamCopyRunnable(InputStream is, OutputStream os,
                              boolean autoClose) {
      this.is = is;
      this.os = os;
      this.autoClose = autoClose;
    }

    public void run() {
      try {
        IOHelper.copyStream(is, os);
      } catch (IOException ex) {
        // Ignore, but stop thread
      } finally {
        if (autoClose) {
          silentClose(is);
          silentClose(os);
        }
      }
    }

    private static void silentClose(Closeable closeable) {
      try {
        closeable.close();
      } catch (IOException ex) {
        // ignore
      }
    }
  }
}
