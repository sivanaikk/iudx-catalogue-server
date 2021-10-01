package iudx.catalogue.server.authenticator;

import iudx.catalogue.server.authenticator.model.JwtData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import iudx.catalogue.server.authenticator.authorization.Api;
import iudx.catalogue.server.authenticator.authorization.AuthorizationContextFactory;
import iudx.catalogue.server.authenticator.authorization.AuthorizationRequest;
import iudx.catalogue.server.authenticator.authorization.AuthorizationStratergy;
import iudx.catalogue.server.authenticator.authorization.JwtAuthorization;
import iudx.catalogue.server.authenticator.authorization.Method;
import iudx.catalogue.server.authenticator.model.JwtData;

import static iudx.catalogue.server.authenticator.Constants.*;
import static iudx.catalogue.server.util.Constants.ID;

/**
 * The JWT Authentication Service Implementation.
 * <h1>JWT Authentication Service Implementation</h1>
 * <p>
 * The JWT Authentication Service implementation in the IUDX Catalogue Server implements the definitions
 * of the {@link iudx.catalogue.server.authenticator.AuthenticationService}.
 * </p>
 *
 * @version 1.0
 * @since 2021-09-23
 */

public class JwtAuthenticationServiceImpl implements AuthenticationService {
  private static final Logger LOGGER = LogManager.getLogger(JwtAuthenticationServiceImpl.class);

  final JWTAuth jwtAuth;
  final String audience;

  JwtAuthenticationServiceImpl(Vertx vertx, final JWTAuth jwtAuth, final JsonObject config) {
    this.jwtAuth = jwtAuth;
    this.audience = config.getString("host");
  }

  Future<JwtData> decodeJwt(String jwtToken) {
    Promise<JwtData> promise = Promise.promise();

    TokenCredentials credentials =  new TokenCredentials(jwtToken);

    jwtAuth.authenticate(credentials)
            .onSuccess(user -> {
              JwtData jwtData = new JwtData(user.principal());
              promise.complete(jwtData);
            }).onFailure(err -> {
              LOGGER.error("failed to decode/validate jwt token : "+err.getMessage());
              promise.fail("failed");
            });
    return promise.future();
  }

  // class to contain intermediate data for token introspection
  final class ResultContainer {
    JwtData jwtData;
  }

  boolean isValidAudienceValue(JwtData jwtData) {
    if(audience !=null && audience.equalsIgnoreCase(jwtData.getAud())) {
      return true;
    } else {
      LOGGER.error("Incorrect audience value in jwt");
      return false;
    }
  }

  boolean isValidId(JwtData jwtData, String id) {
    // id is the (substring [1] of Iid with delimiter as ':' )'s first 2 substrings with delimiter as '/'
    String jwtId = (jwtData.getIid().split(":")[1]).split("/")[0]+"/"+(jwtData.getIid().split(":")[1]).split("/")[1];

    if (id.equalsIgnoreCase(jwtId)) {
      return true;
    } else {
      LOGGER.error("Incorrect id value in jwt");
      return false;
    }
  }

  boolean isValidEndpoint(String endPoint) {
    if(endPoint.equals(ITEM_ENDPOINT) || endPoint.equals(INSTANCE_ENDPOINT)) {
      return true;
    } else {
      LOGGER.error("Incorrect endpoint in jwt");
      return false;
    }
  }

  public Future<JsonObject> validateAccess(JwtData jwtData, JsonObject authenticationInfo) {
    LOGGER.trace("validateAccess() started");
    Promise<JsonObject> promise = Promise.promise();

    Method method = Method.valueOf(authenticationInfo.getString(METHOD));
    Api api = Api.fromEndpoint(authenticationInfo.getString(API_ENDPOINT));

    AuthorizationRequest authRequest = new AuthorizationRequest(method,api);

    AuthorizationStratergy authStrategy = AuthorizationContextFactory.create(jwtData.getRole());
    LOGGER.info("strategy: " + authStrategy.getClass().getSimpleName());

    JwtAuthorization jwtAuthStrategy = new JwtAuthorization(authStrategy);
    LOGGER.info("endpoint: "+authenticationInfo.getString(API_ENDPOINT));

    if(jwtAuthStrategy.isAuthorized(authRequest, jwtData)) {
      LOGGER.info("User access is allowed");
      JsonObject response = new JsonObject().put(JSON_USERID, jwtData.getSub());
      promise.complete(response);
    } else {
      LOGGER.error("user access denied");
      JsonObject result = new JsonObject().put("401","no access provided to endpoint");
      promise.fail(result.toString());
    }
    return promise.future();
  }


  @Override
  public AuthenticationService tokenInterospect(JsonObject request, JsonObject authenticationInfo,
                                                Handler<AsyncResult<JsonObject>> handler) {
    String endPoint = authenticationInfo.getString(API_ENDPOINT);
    String id = authenticationInfo.getString(ID);
    String token = authenticationInfo.getString(TOKEN);

    Future<JwtData> jwtDecodeFuture = decodeJwt(token);

    ResultContainer result = new ResultContainer();

    jwtDecodeFuture.compose(decodeHandler -> {
      result.jwtData =  decodeHandler;
      if(!isValidAudienceValue(result.jwtData)){
        handler.handle(Future.failedFuture("Invalid Audience"));
      }
      if(!isValidId(result.jwtData, id)){
        handler.handle(Future.failedFuture("Invalid User ID"));
      }
      if(!isValidEndpoint(endPoint)){
        handler.handle(Future.failedFuture("Invalid Endpoint"));
      }
      return validateAccess(result.jwtData, authenticationInfo);
    }).onComplete(completeHandler -> {
      LOGGER.debug("completion handler");
      if(completeHandler.succeeded()) {
        handler.handle(Future.succeededFuture(completeHandler.result()));
      } else {
        LOGGER.error("error: " + completeHandler.cause().getMessage());
        handler.handle(Future.failedFuture(completeHandler.cause().getMessage()));
      }
    });
    return this;
  }
}