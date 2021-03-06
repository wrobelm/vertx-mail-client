package io.vertx.ext.mail.impl;

import io.vertx.core.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.Shareable;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.MailResult;

/**
 * MailClient implementation for sending mails inside the local JVM
 *
 * @author <a href="http://oss.lehmann.cx/">Alexander Lehmann</a>
 */
public class MailClientImpl implements MailClient {

  private static final Logger log = LoggerFactory.getLogger(MailClientImpl.class);

  private static final String POOL_LOCAL_MAP_NAME = "__vertx.MailClient.pools";

  private final Vertx vertx;
  private final MailConfig config;
  private final SMTPConnectionPool connectionPool;
  private final MailHolder holder;

  private volatile boolean closed = false;

  public MailClientImpl(Vertx vertx, MailConfig config, String poolName) {
    this.vertx = vertx;
    this.config = config;
    this.holder = lookupHolder(poolName, config);
    this.connectionPool = holder.pool();
  }

  @Override
  public void close() {
    if (closed) {
      throw new IllegalStateException("Already closed");
    }
    holder.close();
    closed = true;
  }

  @Override
  public MailClient sendMail(MailMessage message, Handler<AsyncResult<MailResult>> resultHandler) {
    Context context = vertx.getOrCreateContext();
    if (!closed) {
      if (validateHeaders(message, resultHandler, context)) {
        connectionPool.getConnection(result -> {
          if (result.succeeded()) {
            result.result().setErrorHandler(th -> handleError(th, resultHandler, context));
            sendMessage(message, result.result(), resultHandler, context);
          } else {
            handleError(result.cause(), resultHandler, context);
          }
        });
      }
    } else {
      handleError("mail client has been closed", resultHandler, context);
    }
    return this;
  }

  private void sendMessage(MailMessage email, SMTPConnection conn, Handler<AsyncResult<MailResult>> resultHandler,
      Context context) {
    new SMTPSendMail(conn, email, config, result -> {
      if (result.succeeded()) {
        conn.returnToPool();
      } else {
        conn.setBroken();
      }
      returnResult(result, resultHandler, context);
    }).start();
  }

  // do some validation before we open the connection
  // return true on successful validation so we can stop processing above
  private boolean validateHeaders(MailMessage email, Handler<AsyncResult<MailResult>> resultHandler, Context context) {
    if (email.getBounceAddress() == null && email.getFrom() == null) {
      handleError("sender address is not present", resultHandler, context);
      return false;
    } else if ((email.getTo() == null || email.getTo().size() == 0)
      && (email.getCc() == null || email.getCc().size() == 0)
      && (email.getBcc() == null || email.getBcc().size() == 0)) {
      log.warn("no recipient addresses are present");
      handleError("no recipient addresses are present", resultHandler, context);
      return false;
    } else {
      return true;
    }
  }

  private void handleError(String message, Handler<AsyncResult<MailResult>> resultHandler, Context context) {
    log.debug("handleError:" + message);
    returnResult(Future.failedFuture(message), resultHandler, context);
  }

  private void handleError(Throwable t, Handler<AsyncResult<MailResult>> resultHandler, Context context) {
    log.debug("handleError", t);
    returnResult(Future.failedFuture(t), resultHandler, context);
  }

  private void returnResult(AsyncResult<MailResult> result, Handler<AsyncResult<MailResult>> resultHandler, Context context) {
    // Note - results must always be executed on the right context, asynchronously, not directly!
    context.runOnContext(v -> {
      if (resultHandler != null) {
        resultHandler.handle(result);
      } else {
        if (result.succeeded()) {
          log.debug("dropping sendMail result");
        } else {
          log.info("dropping sendMail failure", result.cause());
        }
      }
    });
  }

  SMTPConnectionPool getConnectionPool() {
    return connectionPool;
  }

  private MailHolder lookupHolder(String poolName, MailConfig config) {
    synchronized (vertx) {
      LocalMap<String, MailHolder> map = vertx.sharedData().getLocalMap(POOL_LOCAL_MAP_NAME);
      MailHolder theHolder = map.get(poolName);
      if (theHolder == null) {
        theHolder = new MailHolder(vertx, config, () -> removeFromMap(map, poolName));
        map.put(poolName, theHolder);
      } else {
        theHolder.incRefCount();
      }
      return theHolder;
    }
  }

  private void removeFromMap(LocalMap<String, MailHolder> map, String dataSourceName) {
    synchronized (vertx) {
      map.remove(dataSourceName);
      if (map.isEmpty()) {
        map.close();
      }
    }
  }

  private static class MailHolder implements Shareable {
    final SMTPConnectionPool pool;
    final Runnable closeRunner;
    int refCount = 1;

    public MailHolder(Vertx vertx, MailConfig config, Runnable closeRunner) {
      this.closeRunner = closeRunner;
      this.pool= new SMTPConnectionPool(vertx, config);
    }

    SMTPConnectionPool pool() {
      return pool;
    }

    synchronized void incRefCount() {
      refCount++;
    }

    synchronized void close() {
      if (--refCount == 0) {
        pool.close();
        if (closeRunner != null) {
          closeRunner.run();
        }
      }
    }
  }
}
