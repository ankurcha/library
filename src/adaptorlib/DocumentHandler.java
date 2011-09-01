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

package adaptorlib;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

class DocumentHandler extends AbstractHandler {
  private static final Logger log
      = Logger.getLogger(AbstractHandler.class.getName());

  private GsaCommunicationHandler commHandler;
  private Adaptor adaptor;
  private Set<InetAddress> gsaAddresses = new HashSet<InetAddress>();

  public DocumentHandler(String defaultHostname, Charset defaultCharset,
                         GsaCommunicationHandler commHandler, Adaptor adaptor,
                         boolean addResolvedGsaHostnameToGsaIps,
                         String gsaHostname, String[] gsaIps) {
    super(defaultHostname, defaultCharset);
    this.commHandler = commHandler;
    this.adaptor = adaptor;

    if (addResolvedGsaHostnameToGsaIps) {
      try {
        gsaAddresses.add(InetAddress.getByName(gsaHostname));
      } catch (UnknownHostException ex) {
        throw new RuntimeException(ex);
      }
    }
    for (String gsaIp : gsaIps) {
      gsaIp = gsaIp.trim();
      if ("".equals(gsaIp)) {
        continue;
      }
      try {
        gsaAddresses.add(InetAddress.getByName(gsaIp));
      } catch (UnknownHostException ex) {
        throw new RuntimeException(ex);
      }
    }
    log.log(Level.INFO, "IPs to believe are the GSA: {0}",
            new Object[] {gsaAddresses});
  }

  private boolean requestIsFromGsa(HttpExchange ex) {
    InetAddress addr = ex.getRemoteAddress().getAddress();
    return gsaAddresses.contains(addr);
  }

  @Override
  public void meteredHandle(HttpExchange ex) throws IOException {
    String requestMethod = ex.getRequestMethod();
    if ("GET".equals(requestMethod) || "HEAD".equals(requestMethod)) {
      /* Call into adaptor developer code to get document bytes. */
      // TODO(ejona): Need to namespace all docids to allow random support URLs
      DocId docId = commHandler.decodeDocId(getRequestUri(ex));
      log.fine("id: " + docId.getUniqueId());

      boolean isAllowed;
      Journal journal = commHandler.getJournal();
      if (requestIsFromGsa(ex)) {
        journal.recordGsaContentRequest(docId);
        isAllowed = true;
      } else {
        journal.recordNonGsaContentRequest(docId);
        // TODO(ejona): add support for authenticating users
        // We do authz with the anonymous user to see if the document is public
        Map<DocId, AuthzStatus> authzMap = adaptor.isUserAuthorized(null,
            Collections.singletonList(docId));
        AuthzStatus status = authzMap != null ? authzMap.get(docId) : null;
        if (status == null) {
          status = AuthzStatus.INDETERMINATE;
          log.log(Level.WARNING, "Adaptor did not provide an authorization "
                  + "result for the requested DocId ''{0}''. Instead provided: "
                  + "{1}", new Object[] {docId, authzMap});
        }
        isAllowed = (status == AuthzStatus.PERMIT);
      }

      if (!isAllowed) {
        cannedRespond(ex, HttpURLConnection.HTTP_FORBIDDEN, "text/plain",
                      "403: Forbidden");
        return;
      }

      DocumentRequest request = new DocumentRequest(ex, docId,
                                                    dateFormat.get());
      DocumentResponse response = new DocumentResponse(ex);
      // TODO(ejona): if text, support providing encoding
      journal.recordRequestProcessingStart();
      byte[] content;
      String contentType;
      int httpResponseCode;
      Metadata metadata;
      try {
        try {
          adaptor.getDocContent(request, response);
        } finally {
          // We want this to be recorded immediately, not after sending error
          // codes
          journal.recordRequestProcessingEnd(response.getWrittenContentSize());
        }

        content = response.getWrittenContent();
        contentType = response.contentType;
        httpResponseCode = response.httpResponseCode;
        metadata = response.metadata;
      } catch (FileNotFoundException e) {
        cannedRespond(ex, HttpURLConnection.HTTP_NOT_FOUND, "text/plain",
                      "Unknown document: " + e.getMessage());
        return;
      } catch (IOException e) {
        cannedRespond(ex, HttpURLConnection.HTTP_INTERNAL_ERROR, "text/plain",
                      "IO Exception: " + e.getMessage());
        return;
      } catch (Exception e) {
        log.log(Level.WARNING, "Unexpected exception from getDocContent", e);
        cannedRespond(ex, HttpURLConnection.HTTP_INTERNAL_ERROR, "text/plain",
                      "Exception (" + e.getClass().getName() + "): "
                      + e.getMessage());
        return;
      }

      if (httpResponseCode != HttpURLConnection.HTTP_OK
          && httpResponseCode != HttpURLConnection.HTTP_NOT_MODIFIED) {
        log.log(Level.WARNING, "Unexpected response code (was any response "
                + "sent from the adaptor?): {0}", httpResponseCode);
        cannedRespond(ex, HttpURLConnection.HTTP_INTERNAL_ERROR, "text/plain",
                      "Tried to return unexpected response code");
        return;
      }

      if (content == null) {
        log.finer("processed request; response is null. This is normal for HEAD"
            + " requests.");
      } else {
        log.finer("processed request; response is size=" + content.length);
      }
      // TODO(ejona): decide when to use compression based on mime-type
      enableCompressionIfSupported(ex);
      if (metadata != null && requestIsFromGsa(ex)) {
        ex.getResponseHeaders().set("X-Gsa-External-Metadata",
                                    formMetadataHeader(metadata));
      }
      if ("GET".equals(requestMethod)) {
        respond(ex, httpResponseCode, contentType, content);
      } else {
        respondToHead(ex, httpResponseCode, contentType);
      }
    } else {
      cannedRespond(ex, HttpURLConnection.HTTP_BAD_METHOD, "text/plain",
                    "Unsupported request method");
    }
  }

  @Override
  protected void respond(HttpExchange ex, int code, String contentType,
                         byte[] response) throws IOException {
    commHandler.getJournal().recordRequestResponseStart();
    try {
      super.respond(ex, code, contentType, response);
    } finally {
      commHandler.getJournal().recordRequestResponseEnd(
          response == null ? 0 : response.length);
    }
  }

  /**
   * Format the GSA-specific metadata header value for crawl-time metadata.
   */
  static String formMetadataHeader(Metadata metadata) {
    StringBuilder sb = new StringBuilder();
    for (MetaItem item : metadata) {
      sb.append(percentEncode(item.getName()));
      sb.append("=");
      sb.append(percentEncode(item.getValue()));
      sb.append(",");
    }
    return (sb.length() == 0) ? "" : sb.substring(0, sb.length() - 1);
  }

  /**
   * Percent-encode {@code value} as described in
   * <a href="http://tools.ietf.org/html/rfc3986#section-2">RFC 3986</a> and
   * using UTF-8. This is the most common form of percent encoding. The
   * characters A-Z, a-z, '-', '_', '.', and '~' are left as-is; the rest are
   * percent encoded.
   */
  static String percentEncode(String value) {
    final Charset encoding = Charset.forName("UTF-8");
    StringBuilder sb = new StringBuilder();
    byte[] bytes = value.getBytes(encoding);
    for (byte b : bytes) {
      if ((b >= 'a' && b <= 'z')
          || (b >= 'A' && b <= 'Z')
          || b == '-' || b == '_' || b == '.' || b == '~') {
        sb.append((char)b);
      } else {
        // Make sure it is positive
        int i = b & 0xff;
        String hex = Integer.toHexString(i).toUpperCase();
        if (hex.length() > 2) {
          throw new IllegalStateException();
        }
        while (hex.length() != 2) {
          hex = "0" + hex;
        }
        sb.append('%').append(hex);
      }
    }
    return sb.toString();
  }

  private static class DocumentRequest implements Adaptor.Request {
    // DateFormats are relatively expensive to create, and cannot be used from
    // multiple threads
    private final DateFormat dateFormat;
    private final HttpExchange ex;
    private final DocId docId;

    private DocumentRequest(HttpExchange ex, DocId docId,
                            DateFormat dateFormat) {
      this.ex = ex;
      this.docId = docId;
      this.dateFormat = dateFormat;
    }

    @Override
    public boolean needDocumentContent() {
      return !"HEAD".equals(ex.getRequestMethod());
    }

    @Override
    public boolean hasChangedSinceLastAccess(Date lastModified) {
      Date date = getLastAccessTime();
      if (date == null) {
        return true;
      }
      return date.before(lastModified);
    }

    @Override
    public Date getLastAccessTime() {
      return getIfModifiedSince(ex);
    }

    @Override
    public DocId getDocId() {
      return docId;
    }
  }

  private static class DocumentResponse implements Adaptor.Response {
    private HttpExchange ex;
    private OutputStream os;
    private String contentType;
    private int httpResponseCode;
    private Metadata metadata;

    public DocumentResponse(HttpExchange ex) {
      this.ex = ex;
      if ("HEAD".equals(ex.getRequestMethod())) {
        // There is no need for them to call getOutputStream
        httpResponseCode = HttpURLConnection.HTTP_OK;
      }
    }

    @Override
    public void respondNotModified() {
      if (os != null) {
        throw new IllegalStateException();
      }
      httpResponseCode = HttpURLConnection.HTTP_NOT_MODIFIED;
      os = new SinkOutputStream();
    }

    @Override
    public OutputStream getOutputStream() {
      if (os != null) {
        return os;
      }
      httpResponseCode = HttpURLConnection.HTTP_OK;
      if ("HEAD".equals(ex.getRequestMethod())) {
        os = new SinkOutputStream();
      } else {
        os = new ByteArrayOutputStream();
      }
      return os;
    }

    @Override
    public void setContentType(String contentType) {
      if (os != null) {
        throw new IllegalStateException();
      }
      this.contentType = contentType;
    }

    @Override
    public void setMetadata(Metadata metadata) {
      if (os != null) {
        throw new IllegalStateException();
      }
      this.metadata = metadata;
    }

    private long getWrittenContentSize() {
      if (os instanceof ByteArrayOutputStream) {
        return ((ByteArrayOutputStream) os).size();
      } else {
        return 0;
      }
    }

    private byte[] getWrittenContent() {
      if (os instanceof ByteArrayOutputStream) {
        return ((ByteArrayOutputStream) os).toByteArray();
      } else {
        return null;
      }
    }
  }


  /**
   * OutputStream that forgets all input. It is equivalent to using /dev/null.
   */
  private static class SinkOutputStream extends OutputStream {
    @Override
    public void write(byte[] b, int off, int len) throws IOException {}

    @Override
    public void write(int b) throws IOException {}
  }

}
