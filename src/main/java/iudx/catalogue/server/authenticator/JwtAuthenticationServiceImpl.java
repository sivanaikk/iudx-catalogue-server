package iudx.catalogue.server.authenticator;

import static iudx.catalogue.server.auditing.util.Constants.*;
import static iudx.catalogue.server.authenticator.Constants.*;
import static iudx.catalogue.server.authenticator.Constants.METHOD;
import static iudx.catalogue.server.util.Constants.*;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import iudx.catalogue.server.authenticator.authorization.AuthorizationContextFactory;
import iudx.catalogue.server.authenticator.authorization.AuthorizationRequest;
import iudx.catalogue.server.authenticator.authorization.AuthorizationStratergy;
import iudx.catalogue.server.authenticator.authorization.JwtAuthorization;
import iudx.catalogue.server.authenticator.authorization.Method;
import iudx.catalogue.server.authenticator.model.JwtData;
import iudx.catalogue.server.util.Api;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The JWT Authentication Service Implementation.
 *
 * <h1>JWT Authentication Service Implementation</h1>
 *
 * <p>The JWT Authentication Service implementation in the IUDX Catalogue Server implements the
 * definitions of the {@link iudx.catalogue.server.authenticator.AuthenticationService}.
 *
 * @version 1.0
 * @since 2021-09-23
 */
public class JwtAuthenticationServiceImpl implements AuthenticationService {
  private static final Logger LOGGER = LogManager.getLogger(JwtAuthenticationServiceImpl.class);

  final JWTAuth jwtAuth;
  final String audience;
  private Api api;

  JwtAuthenticationServiceImpl(
      Vertx vertx, final JWTAuth jwtAuth, final JsonObject config, final Api api) {
    this.jwtAuth = jwtAuth;
    this.audience = config.getString("host");
    this.api = api;
  }

  Future<JwtData> decodeJwt(String jwtToken) {
    Promise<JwtData> promise = Promise.promise();

    TokenCredentials credentials = new TokenCredentials(jwtToken);

    jwtAuth
        .authenticate(credentials)
        .onSuccess(
            user -> {
              JwtData jwtData = new JwtData(user.principal());
              promise.complete(jwtData);
            })
        .onFailure(
            err -> {
              LOGGER.error("failed to decode/validate jwt token : " + err.getMessage());
              promise.fail("failed");
            });
    return promise.future();
  }

  Future<Boolean> isValidAudienceValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();

    LOGGER.debug("Audience in jwt is: " + jwtData.getAud());
    if (audience != null && audience.equalsIgnoreCase(jwtData.getAud())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect audience value in jwt");
      promise.fail("Incorrect audience value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isValidProvider(JwtData jwtData, String provider) {
    Promise<Boolean> promise = Promise.promise();

    String jwtId = "";
    if (jwtData.getRole().equalsIgnoreCase(PROVIDER)) {

      jwtId = jwtData.getSub();
    } else if (jwtData.getRole().equalsIgnoreCase("delegate")) {
      jwtId = jwtData.getDid();
    }

    if (provider.equalsIgnoreCase(jwtId)) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect sub value in jwt");
      promise.fail("Incorrect sub value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isValidEndpoint(String endPoint) {
    Promise<Boolean> promise = Promise.promise();

    LOGGER.debug("Endpoint in JWt is : " + endPoint);
    if (endPoint.equals(api.getRouteItems())
        || endPoint.equals(api.getRouteInstance())
        || endPoint.equals(RATINGS_ENDPOINT)
        || endPoint.equals(MLAYER_INSTANCE_ENDPOINT)
        || endPoint.equals(MLAYER_DOMAIN_ENDPOINT)) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect endpoint in jwt");
      promise.fail("Incorrect endpoint in jwt");
    }
    return promise.future();
  }

  /**
   * Validates the user's access to the given API endpoint based on the user's role and the HTTP
   * method used.
   *
   * @param jwtData the user's JWT data
   * @param authenticationInfo a JsonObject containing the HTTP method
   * @return a Future containing a JsonObject with user information if the access is allowed, or a
   *     failure if the access is denied
   */
  public Future<JsonObject> validateAccess(
      JwtData jwtData, JsonObject authenticationInfo, String itemType) {
    LOGGER.trace("validateAccess() started");
    Promise<JsonObject> promise = Promise.promise();

    Method method = Method.valueOf(authenticationInfo.getString(METHOD));
    String api = authenticationInfo.getString(API_ENDPOINT);

    AuthorizationRequest authRequest = new AuthorizationRequest(method, api, itemType);

    AuthorizationStratergy authStrategy =
        AuthorizationContextFactory.create(jwtData.getRole(), this.api);
    LOGGER.debug("strategy: " + authStrategy.getClass().getSimpleName());

    JwtAuthorization jwtAuthStrategy = new JwtAuthorization(authStrategy);
    LOGGER.debug("endpoint: " + authenticationInfo.getString(API_ENDPOINT));

    if (jwtAuthStrategy.isAuthorized(authRequest)) {
      // Don't allow access to resource server and provider items for anyone except Admin
//      if (ITEM_TYPE_RESOURCE_SERVER.equalsIgnoreCase(itemType)
//          && !authStrategy.getClass().getSimpleName().equalsIgnoreCase("AdminAuthStrategy")) {
//        JsonObject result = new JsonObject().put("401", "no access provided to endpoint");
//        promise.fail(result.toString());
//      }
      LOGGER.debug("User access is allowed");
      JsonObject response = new JsonObject();
      // adding user id, user role and iid to response for auditing purpose
      response.put(USER_ROLE, jwtData.getRole()).put(USER_ID, jwtData.getSub());
      promise.complete(response);
    } else {
      LOGGER.error("user access denied");
      JsonObject result = new JsonObject().put("401", "no access provided to endpoint");
      promise.fail(result.toString());
    }
    return promise.future();
  }

  @Override
  public AuthenticationService tokenInterospect(
      JsonObject request, JsonObject authenticationInfo, Handler<AsyncResult<JsonObject>> handler) {
    String endPoint = authenticationInfo.getString(API_ENDPOINT);
    String provider = authenticationInfo.getString(PROVIDER_KC_ID, "");
    String token = authenticationInfo.getString(TOKEN);
    String itemType = authenticationInfo.getString(ITEM_TYPE,"");
    // TODO: remove rsUrl check
//    String resourceServerUrl = authenticationInfo.getString(RESOURCE_SERVER_URL, "");

    LOGGER.debug("endpoint : " + endPoint);
    Future<JwtData> jwtDecodeFuture = decodeJwt(token);

    ResultContainer result = new ResultContainer();
    // skip provider id check for non-provider operations
    boolean skipProviderIdCheck = provider.equalsIgnoreCase("");
    boolean skipAdminCheck = itemType.equalsIgnoreCase("") ||
        itemType.equalsIgnoreCase(ITEM_TYPE_RESOURCE_GROUP) || itemType.equalsIgnoreCase(ITEM_TYPE_RESOURCE);
    //        api != null && api.getRouteInstance().equalsIgnoreCase(endPoint)
    //            || RATINGS_ENDPOINT.equalsIgnoreCase(endPoint)
    //            || ITEM_TYPE_RESOURCE_SERVER.equalsIgnoreCase(itemType)
    //            || endPoint.contains(MLAYER_BASE_PATH);

    jwtDecodeFuture
        .compose(
            decodeHandler -> {
              result.jwtData = decodeHandler;
              return isValidAudienceValue(result.jwtData);
            })
        .compose(
            audienceHandler -> {
              if (skipProviderIdCheck) {
                return Future.succeededFuture(true);
              } else {
                return isValidProvider(result.jwtData, provider);
              }
            })
        .compose(
            validIdHandler -> {
              return isValidEndpoint(endPoint);
            })
        .compose(
            validEndpointHandler -> {
              // verify admin if itemType is COS/RS/Provider
              if (skipAdminCheck) {
                return Future.succeededFuture(true);
              } else {
                return isValidAdmin(result.jwtData);
              }
            })
        .compose(
            validAdmin -> {
              return validateAccess(result.jwtData, authenticationInfo, itemType);
            })
        .onComplete(
            completeHandler -> {
              if (completeHandler.succeeded()) {
                handler.handle(Future.succeededFuture(completeHandler.result()));
              } else {
                handler.handle(Future.failedFuture(completeHandler.cause().getMessage()));
              }
            });
    return this;
  }

  private Future<Boolean> isValidAdmin(JwtData jwtData) {
    // TODO: cop_admin or cos_admin???
    if (jwtData.getRole().equalsIgnoreCase("cop_admin")) {
      return Future.succeededFuture(true);
    } else if(jwtData.getRole().equalsIgnoreCase("admin")) {
      // TODO: write logic for rs admin check
    } else {
      return Future.failedFuture("admin token required for this operation");
    }
    return null;
  }

  // class to contain intermediate data for token introspection
  final class ResultContainer {
    JwtData jwtData;
  }
}
