package iudx.catalogue.server.geocoding;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.core.CompositeFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.StringBuilder;

import static iudx.catalogue.server.util.Constants.*;

/**
 * The Geocoding Service Implementation.
 *
 * <h1>Geocoding Service Implementation</h1>
 *
 * <p>The Geocoding Service implementation in the IUDX Catalogue Server implements the definitions
 * of the {@link iudx.catalogue.server.geocoding.GeocodingService}.
 *
 * @version 1.0
 * @since 2020-11-05
 */
public class GeocodingServiceImpl implements GeocodingService {

  private static final Logger LOGGER = LogManager.getLogger(GeocodingServiceImpl.class);
  static WebClient webClient;
  private final String peliasUrl;
  private final int peliasPort;
  StringBuilder sb = new StringBuilder();

  public GeocodingServiceImpl(WebClient webClient, String peliasUrl, int peliasPort) {
    this.webClient = webClient;
    this.peliasUrl = peliasUrl;
    this.peliasPort = peliasPort;
  }

  @Override
  public void geocoder(String location, Handler<AsyncResult<String>> handler) {
    webClient
        .get(peliasPort, peliasUrl, "/v1/search")
        .timeout(SERVICE_TIMEOUT)
        .addQueryParam("text", location)
        .putHeader("Accept", "application/json")
        .send(
            ar -> {
              if (ar.succeeded() && ar.result().body().toJsonObject().containsKey("features")) {
                JsonArray feature = ar.result().body().toJsonObject().getJsonArray("features");
                JsonObject property;
                JsonObject features;
                double confidence = 0;
                JsonArray jsonArray = new JsonArray();
                for (int i = 0; i < feature.size(); i++) {
                  features = feature.getJsonObject(i);
                  property = features.getJsonObject("properties");
                  if (confidence < property.getDouble("confidence") && jsonArray.isEmpty()) {
                    System.out.println("here not");
                    confidence = property.getDouble("confidence");
                    JsonObject obj1 = new JsonObject();
                    obj1.put("name", property.getString("name"));
                    obj1.put("country", property.getString("country"));
                    obj1.put("region", property.getString("region"));
                    obj1.put("county", property.getString("county"));
                    obj1.put("locality", property.getString("locality"));
                    obj1.put("borough", property.getString("borough"));
                    obj1.put("bbox", features.getJsonArray("bbox"));
                    jsonArray.add(obj1);
                  } else if (confidence < property.getDouble("confidence")
                      && !jsonArray.isEmpty()) {
                    confidence = property.getDouble("confidence");
                    jsonArray = new JsonArray();
                    JsonObject obj2 = new JsonObject();
                    obj2.put("name", property.getString("name"));
                    obj2.put("country", property.getString("country"));
                    obj2.put("region", property.getString("region"));
                    obj2.put("county", property.getString("county"));
                    obj2.put("locality", property.getString("locality"));
                    obj2.put("borough", property.getString("borough"));
                    obj2.put("bbox", features.getJsonArray("bbox"));
                    jsonArray.add(obj2);
                  } else if (confidence == property.getDouble("confidence")) {
                    confidence = property.getDouble("confidence");
                    JsonObject obj3 = new JsonObject();
                    obj3.put("name", property.getString("name"));
                    obj3.put("country", property.getString("country"));
                    obj3.put("region", property.getString("region"));
                    obj3.put("county", property.getString("county"));
                    obj3.put("locality", property.getString("locality"));
                    obj3.put("borough", property.getString("borough"));
                    obj3.put("bbox", features.getJsonArray("bbox"));
                    jsonArray.add(obj3);
                  }
                }
                LOGGER.debug("Request succeeded!");
                handler.handle(Future.succeededFuture(jsonArray.toString()));

              } else {
                LOGGER.error("Failed to find coordinates");
                handler.handle(Future.failedFuture(ar.cause()));
              }
            });
  }

  private Promise<String> geocoderHelper(String location) {
    Promise<String> promise = Promise.promise();
    geocoder(
        location,
        ar -> {
          if (ar.succeeded()) {
            promise.complete(ar.result());
          } else {
            LOGGER.error("Request failed!");
            promise.complete("");
          }
        });
    return promise;
  }

  @Override
  public void reverseGeocoder(String lat, String lon, Handler<AsyncResult<JsonObject>> handler) {
    webClient
        .get(peliasPort, peliasUrl, "/v1/reverse")
        .timeout(SERVICE_TIMEOUT)
        .addQueryParam("point.lon", lon)
        .addQueryParam("point.lat", lat)
        .putHeader("Accept", "application/json")
        .send(
            ar -> {
              if (ar.succeeded()) {
                LOGGER.debug("Request succeeded!");
                handler.handle(Future.succeededFuture(ar.result().body().toJsonObject()));
              } else {
                LOGGER.error("Failed to find location");
                handler.handle(Future.failedFuture(ar.cause()));
              }
            });
  }

  private Promise<String> reverseGeocoderHelper(String lat, String lon) {
    Promise<String> promise = Promise.promise();
    reverseGeocoder(
        lat,
        lon,
        ar -> {
          if (ar.succeeded()) {
            JsonArray res = ar.result().getJsonArray("features");
            JsonObject properties = res.getJsonObject(0).getJsonObject("properties");
            JsonObject addr = new JsonObject();
            addr.put("name", properties.getString("name"));
            addr.put("borough", properties.getString("borough"));
            addr.put("locality", properties.getString("locality"));
            promise.complete(addr.toString());
          } else {
            LOGGER.error("Request failed!");
            promise.complete("{}");
          }
        });
    return promise;
  }

  @Override
  public void geoSummarize(JsonObject doc, Handler<AsyncResult<String>> handler) {
    Promise<String> p1 = Promise.promise();
    Promise<String> p2 = Promise.promise();

    if (doc.containsKey("location")) {

      /* Geocoding information*/
      JsonObject location = doc.getJsonObject("location");
      String address = location.getString("address");
      if (address != null) {
        p1 = geocoderHelper(address);
      } else {
        p1.complete(new String());
      }

      /* Reverse Geocoding information */
      if (location.containsKey("geometry")) {
        JsonObject geometry = location.getJsonObject("geometry");
        JsonArray pos = geometry.getJsonArray("coordinates");
        String lon = pos.getString(0);
        String lat = pos.getString(1);
        p2 = reverseGeocoderHelper(lat, lon);
      } else {
        p2.complete(new String());
      }
    }
    CompositeFuture.all(p1.future(), p2.future())
        .onSuccess(
            successHandler -> {
              String j1 = successHandler.resultAt(0);
              String j2 = successHandler.resultAt(1);
              JsonObject res = new JsonObject();
              res.put("_geocoded", j1);
              res.put("_reverseGeocoded", j2);
              handler.handle(Future.succeededFuture(res.toString()));
            });
    return;
  }
}
