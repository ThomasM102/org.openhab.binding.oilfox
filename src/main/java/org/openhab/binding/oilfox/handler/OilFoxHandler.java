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

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.oilfox.OilFoxBindingConstants;
import org.openhab.binding.oilfox.internal.OilFoxDeviceConfiguration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 *
 * @author Roland Moser - Initial contribution
 */

@NonNullByDefault
public class OilFoxHandler extends BaseThingHandler implements OilFoxStatusListener {

    private final Logger logger = LoggerFactory.getLogger(OilFoxHandler.class);
    private @Nullable ScheduledFuture<?> deviceRefreshJob;

    public OilFoxHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        @Nullable
        Bridge bridge = this.getBridge(); // prevent race condition
        @Nullable
        ThingStatus bridgeStatus = (bridge == null) ? null : bridge.getStatus();
        String hwid = this.getThing().getProperties().get(OilFoxBindingConstants.PROPERTY_HWID);
        if ((hwid == null) || hwid.isEmpty()) {
            logger.debug("initialize(): {}: hwid not set in thing proberty", this.getThing().getUID());
            // if thing is from texual definition, we find hwid in configuration
            @Nullable
            final OilFoxDeviceConfiguration config = getConfigAs(OilFoxDeviceConfiguration.class);
            hwid = config.hwid;
            if ((hwid == null) || hwid.isEmpty()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "hwid missing");
                return;
            } else {
                logger.debug("initialize(): {}: set hwid {} from thing configuration", this.getThing().getUID(), hwid);
                thing.setProperty("hwid", hwid);
            }
        }
        logger.debug("initialize(): thing ID: {}, hwid: {}, bridge status: {}", getThing().getUID(), hwid,
                bridgeStatus);

        if (bridge != null) {
            if (bridgeStatus == ThingStatus.ONLINE) {
                ThingHandler handler = bridge.getHandler();
                if (handler != null) {
                    ((OilFoxBridgeHandler) handler).registerOilFoxStatusListener(this);
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    ;
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE);
        }
    }

    @Override
    public void dispose() {
        logger.debug("dispose(): hwid {}", this.getThing().getProperties().get(OilFoxBindingConstants.PROPERTY_HWID));
        super.dispose();
    }

    @Override
    public void handleCommand(@Nullable ChannelUID channelUID, Command command) {
        if (channelUID != null) { // if channelUID not set, apply command to all channels
            logger.debug("handleCommand(): channelUID: {}", channelUID);
        }
        logger.debug("handleCommand(): command: {}", command);
        if (command == RefreshType.REFRESH) {
            @Nullable
            Bridge bridge = this.getBridge(); // prevent race condition
            if (bridge == null) {
                logger.error("handleCommand(): bridge not found");
                return;
            }
            @Nullable
            OilFoxBridgeHandler oilfoxBridgeHandler = (OilFoxBridgeHandler) bridge.getHandler(); // get bridge handler
            if (oilfoxBridgeHandler == null) {
                logger.error("handleCommand(): bridge handler not found");
                return;
            }
            oilfoxBridgeHandler.handleCommand(channelUID, command);
            return;
        }
        logger.error("handleCommand(): unknown command: {}", command);
    }

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval():");
        @Nullable
        Bridge bridge = this.getBridge(); // prevent race condition
        if (bridge != null) {
            ThingHandler handler = bridge.getHandler();
            if (handler != null) {
                ((OilFoxBridgeHandler) handler).unregisterOilFoxStatusListener(this);
            }
        }
        super.handleRemoval();
    }

    @Override
    public void onOilFoxRemoved(@Nullable ThingUID bridge, @Nullable String hwid) {
        logger.debug("onOilFoxRemoved(): bridge {}, hwid {}", bridge, hwid);
    }

    @Override
    public void onOilFoxAdded(@Nullable ThingUID bridge, @Nullable String hwid) {
        logger.debug("onOilFoxAdded(): bridge {}, hwid {}", bridge, hwid);
    }

    @Override
    public @Nullable String getHWID() {
        String hwid = this.getThing().getProperties().get(OilFoxBindingConstants.PROPERTY_HWID);
        logger.debug("getHWID(): hwid {}", hwid);
        return hwid;
    }

    @Override
    public void onOilFoxRefresh(JsonArray devices) {
        String hwid = this.getThing().getProperties().get(OilFoxBindingConstants.PROPERTY_HWID);
        logger.debug("onOilFoxRefresh(): refresh hwid {}", hwid);
        if (hwid == null) {
            logger.error("onOilFoxRefresh(): hwid is not set");
            return;
        }

        for (JsonElement device : devices) {
            if (!device.isJsonObject()) {
                continue;
            }
            JsonObject object = device.getAsJsonObject();
            String deviceHWID = object.get("hwid").getAsString();
            logger.debug("onOilFoxRefresh(): source hwid {}", deviceHWID);
            if (!hwid.equals(deviceHWID)) {
                continue;
            }

            String currentMeteringAt = object.get(OilFoxBindingConstants.CHANNEL_CURRENT_METERING_AT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: currentMeteringAt {}", deviceHWID, currentMeteringAt);
            this.updateState(OilFoxBindingConstants.CHANNEL_CURRENT_METERING_AT, new DateTimeType(currentMeteringAt));

            String nextMeteringAt = object.get(OilFoxBindingConstants.CHANNEL_NEXT_METERING_AT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: nextMeteringAt {}", deviceHWID, nextMeteringAt);
            this.updateState(OilFoxBindingConstants.CHANNEL_NEXT_METERING_AT, new DateTimeType(nextMeteringAt));

            // first days this information is missing with a new OilFox device
            @Nullable
            JsonElement daysReachElement = object.get(OilFoxBindingConstants.CHANNEL_DAYS_REACH);
            if (daysReachElement != null) {
                BigInteger daysReach = daysReachElement.getAsBigInteger();
                logger.debug("onOilFoxRefresh(): hwid {}: daysReach {}", deviceHWID, daysReach);
                this.updateState(OilFoxBindingConstants.CHANNEL_DAYS_REACH, DecimalType.valueOf(daysReach.toString()));
            } else {
                logger.debug("onOilFoxRefresh(): hwid {}: daysReach missing from API", deviceHWID);
            }

            String batteryLevel = object.get(OilFoxBindingConstants.CHANNEL_BATTERY_LEVEL).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: batteryLevel {}", deviceHWID, batteryLevel);
            this.updateState(OilFoxBindingConstants.CHANNEL_BATTERY_LEVEL, new StringType(batteryLevel));

            BigInteger fillLevelPercent = object.get(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_PERCENT)
                    .getAsBigInteger();
            logger.debug("onOilFoxRefresh(): hwid {}: fillLevelPercent {}", deviceHWID, fillLevelPercent);
            this.updateState(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_PERCENT,
                    DecimalType.valueOf(fillLevelPercent.toString()));

            BigInteger fillLevelQuantity = object.get(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_QUANTITY)
                    .getAsBigInteger();
            logger.debug("onOilFoxRefresh(): hwid {}: fillLevelQuantity {}", deviceHWID, fillLevelQuantity);
            this.updateState(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_QUANTITY,
                    DecimalType.valueOf(fillLevelQuantity.toString()));

            String quantityUnit = object.get(OilFoxBindingConstants.CHANNEL_QUANTITY_UNIT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: quantityUnit {}", deviceHWID, quantityUnit);
            this.updateState(OilFoxBindingConstants.CHANNEL_QUANTITY_UNIT, new StringType(quantityUnit));

            updateStatus(ThingStatus.ONLINE);

            // schedule additional refresh to time short after next metering
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));
            ZonedDateTime dateTimeWithZoneOffset = ZonedDateTime.parse(nextMeteringAt, formatter);
            LocalDateTime dateTime = LocalDateTime.ofInstant(dateTimeWithZoneOffset.toInstant(),
                    ZoneId.systemDefault());
            long minutes = MINUTES.between(LocalDateTime.now(), dateTime) + 5; // add 5 minutes to be save to get new
            // cancel old job
            ScheduledFuture<?> localDeviceRefreshJob = this.deviceRefreshJob; // prevent race condition
            if (localDeviceRefreshJob != null) {
                logger.debug("onOilFoxRefresh(): hwid {}: cancel additional refresh schedule", deviceHWID);
                localDeviceRefreshJob.cancel(false);
            }
            logger.debug("onOilFoxRefresh(): hwid {}: add additional refresh schedule in {} minutes", deviceHWID,
                    minutes);
            deviceRefreshJob = scheduler.schedule(() -> {
                handleCommand(null, RefreshType.REFRESH);
            }, minutes, TimeUnit.MINUTES);
            return;
        }

        // Oilfox not found
        updateStatus(ThingStatus.OFFLINE);
    }
}
