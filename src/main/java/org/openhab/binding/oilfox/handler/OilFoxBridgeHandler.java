/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
import java.io.InterruptedIOException;
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
 * @author Thomas M. - adapted to new FoxInsights Customer API
 */
@NonNullByDefault
public class OilFoxBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(OilFoxBridgeHandler.class);

    private OilFoxBridgeConfiguration config = getConfigAs(OilFoxBridgeConfiguration.class);
    private @Nullable ScheduledFuture<?> refreshJob;
    private List<OilFoxStatusListener> oilFoxStatusListeners = new CopyOnWriteArrayList<>();
    private @Nullable String accessToken = null;
    private LocalDateTime accessTokenTime = LocalDateTime.now();
    private LocalDateTime lastDeviceRefresh = LocalDateTime.now();
    private @Nullable String refreshToken = null;

    public OilFoxBridgeHandler(Bridge bridge) {
        super(bridge);
        String bridgeUID = this.getThing().getUID().toString();
        logger.debug("OilFoxBridgeHandler(): bridge UID {}: thing created", bridgeUID);
    }

    private void readStatus() {
        synchronized (this) {
            logger.debug("readStatus(): started");
            if (!login()) { // login FoxInsights Customer API or refresh access token
                logger.error("readStatus(): login failed");
                return; // login failed
            }
            logger.debug("readStatus(): login successful");

            if (getThing().getStatus() != ThingStatus.ONLINE) {
                return;
            }

            try {
                JsonElement responseObject = getAllDevices();
                if (responseObject == null) {
                    logger.debug("readStatus(): responseObject is null");
                    return;
                }
                if (responseObject.isJsonObject()) {
                    JsonObject object = responseObject.getAsJsonObject();
                    JsonArray devices = object.get("items").getAsJsonArray();

                    updateStatus(ThingStatus.ONLINE);

                    for (OilFoxStatusListener oilFoxStatusListener : oilFoxStatusListeners) {
                        oilFoxStatusListener.onOilFoxRefresh(devices);
                    }
                }
            } catch (InterruptedIOException e) {
                logger.debug("readStatus(): request interrupted {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            } catch (IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            }
        }
    }

    @Override
    public void handleCommand(@Nullable ChannelUID channelUID, Command command) {
        if (channelUID != null) { // if channelUID not set, apply command to all channels
            logger.debug("handleCommand(): channelUID: {}", channelUID);
        }
        logger.debug("handleCommand(): command: {}", command);
        if (command == RefreshType.REFRESH) {
            // prevent to overload API fair use from additional refresh at metering time
            if (channelUID == null) { // called by additional refresh schedule
                long minutes = MINUTES.between(lastDeviceRefresh, LocalDateTime.now());
                logger.debug("handleCommand(): last additional device refresh {} minutes ago", minutes);
                if (minutes < 60) { // Fair Use Policy: "Getting the status of all of your device every hour is
                                    // considered to be of fair use and no rate limiting is applied."
                    logger.debug("handleCommand(): too fast refresh, defer request");
                    return;
                }
                lastDeviceRefresh = LocalDateTime.now();
            }
            readStatus();
            return;
        }
        logger.error("handleCommand(): unknown command: {}", command);
    }

    @Override
    public void initialize() {
        logger.debug("initialize(): bridge UID {}", this.getThing().getUID().toString());
        // reset config, maybe settings changed
        config = getConfigAs(OilFoxBridgeConfiguration.class);
        accessToken = null;
        refreshToken = null;
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

    @Override
    public void dispose() {
        String bridgeUID = this.getThing().getUID().toString();
        logger.debug("dispose(): bridge UID {}", bridgeUID);
        // remove refresh schedule
        ScheduledFuture<?> localRefreshJob = this.refreshJob; // prevent race condition
        if (localRefreshJob != null) {
            logger.debug("dispose(): bridge UID {}: cancel refresh schedule", bridgeUID);
            localRefreshJob.cancel(false);
        }
        super.dispose();
    }

    // communication with OilFox Cloud
    @Nullable
    protected JsonElement query(String address) throws MalformedURLException, IOException {
        return query(address, JsonNull.INSTANCE);
    }

    @Nullable
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
                case 200: // authorized
                    try {
                        Reader reader = new InputStreamReader(request.getInputStream(), "UTF-8");
                        JsonElement element = JsonParser.parseReader(reader);
                        reader.close();
                        logger.debug("query(): response {}", element.toString());
                        return element;
                    } catch (InterruptedIOException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                        logger.error("query(): request interrupted {}", e.getMessage());
                    } catch (IOException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                        logger.error("query(): IOException {}", e.getMessage());
                    }
                    break;
                case 401:
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "query request failed: password invalid");
                    logger.error("query(): request failed, password invalid");
                    break;
                case 404:
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "query request failed: user " + config.email + "not valid");
                    logger.error("query(): request failed, user {} not valid", config.email);
                    break;
                case 429:
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "query request failed: Too Many Requests");
                    logger.error("query(): request failed, Too Many Requests");
                    break;
                default:
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "query request failed, response code " + request.getResponseCode());
                    logger.error("query(): request failed, response code {}", request.getResponseCode());
            }
        } catch (URISyntaxException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            throw new MalformedURLException("invalid url");
        } catch (InterruptedIOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            logger.error("query(): failed with InterruptedIOException: {}", e.getMessage());
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            logger.error("query(): failed with IOException: {}", e.getMessage());
        }
        return null;
    }

    @Nullable
    protected JsonElement queryRefreshToken() throws MalformedURLException, IOException {
        try {
            URL url = new URI("https://" + config.address + "/customer-api/v1/token").toURL();
            logger.debug("queryRefreshToken(): url: {}", url.toString());

            String payload = "refresh_token=" + refreshToken;
            logger.debug("queryRefreshToken(): payload: {}", payload);

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
                        logger.debug("queryRefreshToken(): response {}", element.toString());
                        return element;
                    } catch (InterruptedIOException e) {
                        logger.debug("queryRefreshToken(): request interrupted {}", e.getMessage());
                        throw new IOException("query(): create InputStreamReader() failed");
                    } catch (IOException e) {
                        throw new IOException("queryRefreshToken(): create InputStreamReader() failed");
                    }
                default:
                    // refresh token invalid
                    logger.debug("queryRefreshToken(): refresh access token failed, response code {}",
                            request.getResponseCode());
                    accessToken = null;
                    refreshToken = null;
                    return null;
            }
        } catch (URISyntaxException e) {
            throw new MalformedURLException("invalid url");
        }
    }

    private boolean login() {
        if (refreshToken != null) { // we have a refresh access token, use this
            long minutes = MINUTES.between(accessTokenTime, LocalDateTime.now());
            if (minutes < 15) {
                logger.debug("login(): access token age {} minutes, no need to refresh", minutes);
                return true;
            }
            logger.debug("login(): access token age {} minutes, need to refresh", minutes);
            try {
                JsonElement responseObject = queryRefreshToken();
                if (responseObject != null) {
                    logger.trace("login(): responseObject: {}", responseObject.toString());

                    if (responseObject.isJsonObject()) {
                        JsonObject object = responseObject.getAsJsonObject();
                        accessToken = object.get("access_token").getAsString();
                        accessTokenTime = LocalDateTime.now();
                        refreshToken = object.get("refresh_token").getAsString();
                        logger.debug("login(): access token: {}", accessToken);
                        logger.debug("login(): refresh token: {}", refreshToken);
                        updateStatus(ThingStatus.ONLINE);
                        return true; // refresh access token was succesful
                    }
                }
            } catch (InterruptedIOException e) {
                // do not set thing OFFLINE, retry with user/password
                logger.debug("login(): refresh token exception InterruptedIOException {}", e.getMessage());
            } catch (IOException e) {
                // do not set thing OFFLINE, retry with user/password
                logger.debug("login(): refresh token exception IOException {}", e.getMessage());
            }
        }

        // login with user/password
        logger.debug("login(): login to FoxInsights API with user and password");
        try {
            JsonObject requestObject = new JsonObject();
            requestObject.addProperty("email", config.email);
            requestObject.addProperty("password", config.password);

            JsonElement responseObject = query("/customer-api/v1/login", requestObject);
            if (responseObject == null) {
                logger.debug("login(): responseObject is null");
                return false;
            }
            logger.trace("login(): responseObject: {}", responseObject.toString());

            if (responseObject.isJsonObject()) {
                JsonObject object = responseObject.getAsJsonObject();
                accessToken = object.get("access_token").getAsString();
                accessTokenTime = LocalDateTime.now();
                refreshToken = object.get("refresh_token").getAsString();
                logger.debug("login(): access token: {}", accessToken);
                logger.debug("login(): refresh token: {}", refreshToken);
            } else {
                logger.error("login(): invalid responseObject");
                return false;
            }
            updateStatus(ThingStatus.ONLINE);
            return true;
        } catch (InterruptedIOException e) {
            logger.debug("login(): user/password InterruptedIOException: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());

        } catch (IOException e) {
            logger.error("login(): user/password IOException: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
        return false;
    }

    @Nullable
    public JsonElement getAllDevices() throws MalformedURLException, IOException {
        JsonElement responseObject = query("/customer-api/v1/device");
        if (responseObject == null) {
            logger.debug("getAllDevices(): responseObject is null");
            return null;
        }
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
                            return null;
                        }
                    }
                }
            }
        } else {
            logger.error("getAllDevices(): invalid responseObject");
            return null;
        }
        return responseObject;
    }

    public boolean registerOilFoxStatusListener(OilFoxStatusListener oilFoxStatusListener) {
        logger.debug("registerOilFoxStatusListener(): bridge UID {}", this.getThing().getUID().toString());
        return oilFoxStatusListeners.add(oilFoxStatusListener);
    }

    public boolean unregisterOilFoxStatusListener(OilFoxStatusListener oilFoxStatusListener) {
        logger.debug("unregisterOilFoxStatusListener():");
        return oilFoxStatusListeners.remove(oilFoxStatusListener);
    }
}
