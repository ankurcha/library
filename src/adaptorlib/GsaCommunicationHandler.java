package adaptorlib;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

/** This class handles the communications with GSA. */
public class GsaCommunicationHandler {
  private static final Logger LOG
      = Logger.getLogger(GsaCommunicationHandler.class.getName());

  private final Adaptor adaptor;
  private final Config config;
  private final GsaFeedFileSender fileSender;
  private final GsaFeedFileMaker fileMaker;

  public GsaCommunicationHandler(Adaptor adaptor, Config config) {
    this.adaptor = adaptor;
    this.config = config;
    this.fileSender = new GsaFeedFileSender(config.getGsaCharacterEncoding());
    this.fileMaker = new GsaFeedFileMaker(this);
  }

  /** Starts listening for communications from GSA. */
  public void beginListeningForContentRequests() throws IOException {
    int port = config.getServerPort();
    InetSocketAddress addr = new InetSocketAddress(port);
    HttpServer server = HttpServer.create(addr, 0);
    server.createContext("/sso", new SsoHandler(config.getServerHostname(),
        config.getGsaCharacterEncoding()));
    // Disable SecurityHandler until it can query adapter for configuration
    server.createContext(config.getServerBaseUri().getPath()
        + config.getServerDocIdPath(),
        new DocumentHandler(config.getServerHostname(),
                            config.getGsaCharacterEncoding(), this, adaptor));
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    LOG.info("GSA host name: " + config.getGsaHostname());
    LOG.info("server is listening on port #" + port);
  }

  public void beginPushingDocIds(ScheduleIterator schedule) {
    Scheduler pushScheduler = new Scheduler();
    pushScheduler.schedule(new Scheduler.Task() {
      public void run() {
        // TODO: Prevent two simultenous calls.
        LOG.info("about to get doc ids");
           List<DocId> handles;
          try {
            handles = adaptor.getDocIds();
            LOG.info("about to push " + handles.size() + " doc ids");
            pushDocIds("testfeed", handles);
          } catch (IOException e) {
            // TODO(johnfelton): Improve error recording when "journal" is available.
            LOG.severe(e.getMessage());
          }
        LOG.info("done pushing doc ids");
      }
    }, schedule);
  }

  private void pushSizedBatchOfDocIds(String feedSourceName,
                                      List<DocId> handles) {
    String xmlFeedFile = fileMaker.makeMetadataAndUrlXml(
        feedSourceName, handles);
    boolean keepGoing = true;
    for (int ntries = 0; keepGoing; ntries++) {
      try {
        LOG.info("Sending feed to GSA host name: " + config.getGsaHostname());
        fileSender.sendMetadataAndUrl(config.getGsaHostname(), feedSourceName,
                                  xmlFeedFile);
        keepGoing = false;  // Sent.
      } catch (GsaFeedFileSender.FailedToConnect ftc) {
        LOG.warning("" + ftc);
        keepGoing = adaptor.handleFailedToConnect(ftc, ntries);
      } catch (GsaFeedFileSender.FailedWriting fw) {
        LOG.warning("" + fw);
        keepGoing = adaptor.handleFailedToConnect(fw, ntries);
      } catch (GsaFeedFileSender.FailedReadingReply fr) {
        LOG.warning("" + fr);
        keepGoing = adaptor.handleFailedToConnect(fr, ntries);
      }
    }
  }

  /** Makes and sends metadata-and-url feed files to GSA. */
  public void pushDocIds(String feedSourceName, List<DocId> handles) {
    final int MAX = config.getFeedMaxUrls();
    int totalPushed = 0;
    for (int i = 0; i < handles.size(); i += MAX) {
      int endIndex = i + MAX;
      if (endIndex > handles.size()) {
        endIndex = handles.size();
      }
      List<DocId> batch = handles.subList(i, endIndex);
      pushSizedBatchOfDocIds(feedSourceName, batch);
      totalPushed += batch.size();
    }
    if (handles.size() != totalPushed) {
      throw new IllegalStateException();
    }
  }

  URI encodeDocId(DocId docId) {
    if (config.isDocIdUrl()) {
      return URI.create(docId.getUniqueId());
    } else {
      URI base = config.getServerBaseUri(docId);
      URI resource;
      try {
        resource = new URI(null, null, base.getPath()
                           + config.getServerDocIdPath() + docId.getUniqueId(),
                           null);
      } catch (URISyntaxException ex) {
        throw new IllegalStateException(ex);
      }
      return base.resolve(resource);
    }
  }

  /** Given a URI that was used in feed file, convert back to doc id. */
  DocId decodeDocId(URI uri) {
    if (config.isDocIdUrl()) {
      return new DocId(uri.toString());
    } else {
      String basePath = config.getServerBaseUri().getPath();
      String id = uri.getPath().substring(basePath.length()
          + config.getServerDocIdPath().length());
      return new DocId(id);
    }
  }

  URI formNamespacedUri(String namespace) {
    URI uri;
    try {
      uri = new URI(null, null, config.getServerBaseUri().getPath() + "/sso",
                    null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
    return config.getServerBaseUri().resolve(uri);
  }
}