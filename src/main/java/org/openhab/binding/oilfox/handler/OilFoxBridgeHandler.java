/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.oilfox.handler;

import static java.time.temporal.ChronoUnit.MINUTES;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.oilfox.internal.OilFoxBridgeConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link OilFoxBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Roland Moser - Initial contribution
 */
@NonNullByDefault
public class OilFoxBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(OilFoxBridgeHandler.class);

    private OilFoxBridgeConfiguration config = getConfigAs(OilFoxBridgeConfiguration.class);
    private @Nullable ScheduledFuture<?> refreshJob;
    private List<OilFoxStatusListener> oilFoxStatusListeners = new CopyOnWriteArrayList<>();
    private @Nullable String accessToken = null;
    private LocalDateTime accessTokenTime = LocalDateTime.now();
    private @Nullable String refreshToken = null;

    public OilFoxBridgeHandler(Bridge bridge) {
        super(bridge);
        logger.debug("OilFoxBridgeHandler(): create object");
    }

    private void readStatus() {
        synchronized (this) {
            logger.debug("readStatus(): started");
            login(); // refresh access token

            if (getThing().getStatus() != ThingStatus.ONLINE) {
                return;
            }

            try {
                JsonElement responseObject = getAllDevices();
                if (responseObject.isJsonObject()) {
                    JsonObject object = responseObject.getAsJsonObject();
                    JsonArray devices = object.get("items").getAsJsonArray();

                    updateStatus(ThingStatus.ONLINE);

                    for (OilFoxStatusListener oilFoxStatusListener : oilFoxStatusListeners) {
                        oilFoxStatusListener.onOilFoxRefresh(devices);
                    }
                }
            } catch (IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand(): channelUID {}, command {}", channelUID, command);
        if (command == RefreshType.REFRESH) {
            readStatus();
            return;
        }
        logger.error("handleCommand(): unknown command: {}", command);
    }

    @Override
    public void initialize() {
        config = getConfigAs(OilFoxBridgeConfiguration.class); // reload config, maybe settings changed
        // logger.debug("initialize(): address: {}", config.address);
        // logger.debug("initialize(): email: {}", config.email);
        // logger.debug("initialize(): password: {}", config.password);
        // logger.debug("initialize(): refresh: {}", config.refresh);
        synchronized (this) {
            // cancel old job
            ScheduledFuture<?> localRefreshJob = this.refreshJob; // prevent race condition
            if (localRefreshJob != null) {
                localRefreshJob.cancel(false);
            }
            refreshJob = scheduler.scheduleWithFixedDelay(() -> {
                readStatus();
            }, 0, config.refresh.longValue(), TimeUnit.HOURS);

            updateStatus(ThingStatus.ONLINE);
        }
    }

    // communication with OilFox Cloud

    protected JsonElement query(String address) throws MalformedURLException, IOException {
        return query(address, JsonNull.INSTANCE);
    }

    protected JsonElement query(String path, JsonElement requestObject) throws MalformedURLException, IOException {
        try {
            URL url = new URI("https://" + config.address + path).toURL();
            logger.debug("query(): {}", url.toString());
            HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
            request.setReadTimeout(10000);
            request.setConnectTimeout(15000);
            request.setRequestProperty("Content-Type", "application/json");
            request.setDoInput(true);
            if (requestObject == JsonNull.INSTANCE) { // used by getAllDevices
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    throw new IOException("Not logged in");
                }
                logger.debug("query(): access token: {}", accessToken);
                request.setRequestProperty("Authorization", "Bearer " + accessToken);
            } else { // used by login()
                request.setRequestMethod("POST");
                request.setDoOutput(true);

                OutputStream os = request.getOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                writer.write(requestObject.toString());
                writer.flush();
                writer.close();
                os.close();
            }

            request.connect();

            switch (request.getResponseCode()) {
                case 400:
                    logger.error("query(): login to FoxInsights API failed");
                    throw new IOException("login to FoxInsights API failed");
                case 401:
                    logger.error("query(): request is unauthorized");
                    throw new IOException("FoxInsights API Unauthorized");
                case 200:
                    // authorized
                default:
                    try (Reader reader = new InputStreamReader(request.getInputStream(), "UTF-8")) {
                        JsonElement element = JsonParser.parseReader(reader);
                        reader.close();
                        logger.debug("query(): respose {}", element.toString());
                        return element;
                    } catch (IOException e) {
                        throw new IOException("query(): create InputStreamReader() failed");
                    }
            }
        } catch (URISyntaxException e) {
            throw new MalformedURLException("invalid url");
        }
    }

    protected JsonElement queryRefreshToken(String path, String payload) throws MalformedURLException, IOException {
        try {
            URL url = new URI("https://" + config.address + path).toURL();
            logger.debug("queryRefreshToken(): url {}", url.toString());
            logger.debug("queryRefreshToken(): payload {}", payload);
            HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
            request.setReadTimeout(10000);
            request.setConnectTimeout(15000);
            request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            request.setDoInput(true);
            request.setRequestMethod("POST");
            request.setDoOutput(true);

            OutputStream os = request.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(payload);
            writer.flush();
            writer.close();
            os.close();
            request.connect();

            switch (request.getResponseCode()) {
                case 200:
                    // authorized
                    try (Reader reader = new InputStreamReader(request.getInputStream(), "UTF-8")) {
                        JsonElement element = JsonParser.parseReader(reader);
                        reader.close();
                        logger.debug("queryRefreshToken(): respose {}", element.toString());
                        return element;
                    } catch (IOException e) {
                        throw new IOException("query(): create InputStreamReader() failed");
                    }
                default:
                    logger.error("queryRefreshToken(): login to FoxInsights API failed, response code {}",
                            request.getResponseCode());
                    accessToken = null;
                    refreshToken = null;
                    throw new IOException("login to FoxInsights API failed");
            }
        } catch (URISyntaxException e) {
            throw new MalformedURLException("invalid url");
        }
    }

    private void login() {
        if (accessToken == null) { // login with user and password
            logger.debug("login(): no access token, login to FoxInsights API with user and password");
            try {
                JsonObject requestObject = new JsonObject();
                requestObject.addProperty("email", config.email);
                requestObject.addProperty("password", config.password);

                JsonElement responseObject = query("/customer-api/v1/login", requestObject);
                logger.trace("login(): responseObject: {}", responseObject.toString());

                if (responseObject.isJsonObject()) {
                    JsonObject object = responseObject.getAsJsonObject();
                    accessToken = object.get("access_token").getAsString();
                    accessTokenTime = LocalDateTime.now();
                    refreshToken = object.get("refresh_token").getAsString();
                    logger.debug("login(): access token: {}", accessToken);
                    logger.debug("login(): refresh token: {}", refreshToken);
                }
                updateStatus(ThingStatus.ONLINE);
            } catch (IOException e) {
                logger.error("login(): exception occurred during login with user and password: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        } else { // refresh access token
            long minutes = MINUTES.between(accessTokenTime, LocalDateTime.now());
            if (minutes < 15) {
                logger.debug("login(): access token age {} minutes, no need to refresh", minutes);
                return;
            }
            logger.debug("login(): refresh access token on FoxInsights API");
            try {
                String payload = "refreshToken=" + refreshToken;
                // StringEntity entity = new StringEntity(payload, ContentType.APPLICATION_FORM_URLENCODED);
                JsonElement responseObject = queryRefreshToken("/customer-api/v1/token", payload);
                logger.trace("login(): responseObject: {}", responseObject.toString());

                if (responseObject.isJsonObject()) {
                    JsonObject object = responseObject.getAsJsonObject();
                    accessToken = object.get("access_token").getAsString();
                    accessTokenTime = LocalDateTime.now();
                    refreshToken = object.get("refresh_token").getAsString();
                    logger.debug("login(): access token: {}", accessToken);
                    logger.debug("login(): refresh token: {}", refreshToken);
                }

                updateStatus(ThingStatus.ONLINE);
            } catch (IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        }
    }

    public JsonElement getAllDevices() throws MalformedURLException, IOException {
        JsonElement responseObject = query("/customer-api/v1/device");
        logger.debug("getAllDevices(): responseObject: {}", responseObject.toString());

        if (responseObject.isJsonObject()) {
            JsonObject object = responseObject.getAsJsonObject();
            JsonArray devices = object.get("items").getAsJsonArray();

            for (JsonElement device : devices) {
                String hwid = device.getAsJsonObject().get("hwid").getAsString();
                logger.debug("getAllDevices(): device from API with hwid: {}", hwid);
                // check if device with same hwid exists, HWID must be unique
                boolean found = false;
                for (OilFoxStatusListener oilFoxStatusListener : oilFoxStatusListeners) {
                    @Nullable
                    String existingHWID = oilFoxStatusListener.getHWID();
                    if (existingHWID == null) {
                        continue;
                    }
                    logger.debug("getAllDevices(): existing device HWID {}", existingHWID);
                    if (hwid.equals(existingHWID)) {
                        logger.debug("getAllDevices(): device with hwid {} exists", hwid);
                        found = true;
                    }
                }
                // add new found device
                if (!found) {
                    for (OilFoxStatusListener oilFoxStatusListener : oilFoxStatusListeners) {
                        try {
                            oilFoxStatusListener.onOilFoxAdded(this.getThing().getUID(), hwid);
                        } catch (Exception e) {
                            logger.error("An exception occurred while calling the OilFoxStatusListener", e);
                        }
                    }
                }
            }
        }
        return responseObject;
    }

    public boolean registerOilFoxStatusListener(@Nullable OilFoxStatusListener oilFoxStatusListener) {
        logger.debug("registerOilFoxStatusListener():");
        if (oilFoxStatusListener == null) {
            throw new IllegalArgumentException("It's not allowed to pass a null OilFoxStatusListener.");
        }
        return oilFoxStatusListeners.add(oilFoxStatusListener);
    }

    public boolean unregisterOilFoxStatusListener(OilFoxStatusListener oilFoxStatusListener) {
        logger.debug("unregisterOilFoxStatusListener():");
        return oilFoxStatusListeners.remove(oilFoxStatusListener);
    }
}
