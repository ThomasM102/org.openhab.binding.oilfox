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
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
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
 * @author Thomas M. - adapted to new FoxInsights Customer API
 */

@NonNullByDefault
public class OilFoxHandler extends BaseThingHandler implements OilFoxStatusListener {

    private final Logger logger = LoggerFactory.getLogger(OilFoxHandler.class);
    private @Nullable ScheduledFuture<?> deviceRefreshJob;
    private LocalDateTime lastDeviceRefresh = LocalDateTime.now().minusDays(1); // make sure initial value in past

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
                logger.error("initialize(): hwid missing");
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "hwid missing");
                return;
            } else {
                logger.debug("initialize(): {}: set hwid {} from thing configuration", this.getThing().getUID(), hwid);
                thing.setProperty("hwid", hwid);
            }
        }

        if (bridge != null) {
            if (bridgeStatus == ThingStatus.ONLINE) {
                ThingHandler handler = bridge.getHandler();
                if (handler != null) {
                    logger.debug("initialize(): thingID: {}, hwid: {}: register status listener", getThing().getUID(),
                            hwid);
                    ((OilFoxBridgeHandler) handler).registerOilFoxStatusListener(this);
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    ;
                }
            } else {
                logger.debug("initialize(): thingID: {}, hwid: {}: invalid bridge status: {}", getThing().getUID(),
                        hwid, bridgeStatus);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            }
        } else {
            logger.error("initialize(): hwid {]: bridge undefined");
            updateStatus(ThingStatus.OFFLINE);
        }
    }

    @Override
    public void dispose() {
        String hwid = this.getThing().getProperties().get(OilFoxBindingConstants.PROPERTY_HWID);
        logger.debug("dispose(): hwid {}", hwid);
        // unregister listener
        @Nullable
        Bridge bridge = this.getBridge(); // prevent race condition
        if (bridge != null) {
            ThingHandler handler = bridge.getHandler();
            if (handler != null) {
                logger.debug("dispose(): thingID: {}, hwid: {}: unregister status listener", getThing().getUID(), hwid);
                ((OilFoxBridgeHandler) handler).unregisterOilFoxStatusListener(this);
            }
        }
        // remove additional schedule
        ScheduledFuture<?> localDeviceRefreshJob = this.deviceRefreshJob; // prevent race condition
        if (localDeviceRefreshJob != null) {
            logger.debug("dispose(): hwid {}: cancel additional refresh schedule", hwid);
            localDeviceRefreshJob.cancel(false);
        }
        super.dispose();
    }

    @Override

    public void handleCommand(@Nullable ChannelUID channelUID, Command command) {
        if (channelUID != null) { // if channelUID not set, apply command to all channels
            logger.debug("handleCommand(): hwid {} channelUID: {}", getHWID(), channelUID);
        }
        logger.debug("handleCommand(): command: {}", command);
        if (command == RefreshType.REFRESH) {
            @Nullable
            Bridge bridge = this.getBridge(); // prevent race condition
            if (bridge == null) {
                logger.error("handleCommand(): hwid {}: bridge not found", getHWID());
                return;
            }
            @Nullable
            OilFoxBridgeHandler oilfoxBridgeHandler = (OilFoxBridgeHandler) bridge.getHandler(); // get bridge handler
            if (oilfoxBridgeHandler == null) {
                logger.error("handleCommand(): hwid {}: bridge handler not found", getHWID());
                return;
            }
            oilfoxBridgeHandler.handleCommand(channelUID, command);
            return;
        }
        logger.error("handleCommand(): hwid {}: unknown command: {}", getHWID(), command);
    }

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval(): hwid {}", getHWID());
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
        return hwid;
    }

    @Override
    public void onOilFoxRefresh(JsonArray devices) {
        String hwid = getHWID();
        logger.debug("onOilFoxRefresh(): hwid {}: refresh channels", hwid);
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
            logger.trace("onOilFoxRefresh(): source hwid {}", deviceHWID);
            if (!hwid.equals(deviceHWID)) {
                continue;
            }

            String currentMeteringAt = object.get(OilFoxBindingConstants.OILFOX_CURRENT_METERING_AT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: currentMeteringAt {}", deviceHWID, currentMeteringAt);
            this.updateState(OilFoxBindingConstants.CHANNEL_CURRENT_METERING_AT, new DateTimeType(currentMeteringAt));

            String nextMeteringAt = object.get(OilFoxBindingConstants.OILFOX_NEXT_METERING_AT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: nextMeteringAt {}", deviceHWID, nextMeteringAt);
            this.updateState(OilFoxBindingConstants.CHANNEL_NEXT_METERING_AT, new DateTimeType(nextMeteringAt));

            // first days this information is missing with a new OilFox device
            @Nullable
            JsonElement daysReachElement = object.get(OilFoxBindingConstants.OILFOX_DAYS_REACH);
            if (daysReachElement != null) {
                BigInteger daysReach = daysReachElement.getAsBigInteger();
                logger.debug("onOilFoxRefresh(): hwid {}: daysReach {}", deviceHWID, daysReach);
                this.updateState(OilFoxBindingConstants.CHANNEL_DAYS_REACH, DecimalType.valueOf(daysReach.toString()));
            } else {
                logger.debug("onOilFoxRefresh(): hwid {}: daysReach missing from API", deviceHWID);
            }

            String batteryLevel = object.get(OilFoxBindingConstants.OILFOX_BATTERY_LEVEL).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: batteryLevel {}", deviceHWID, batteryLevel);
            this.updateState(OilFoxBindingConstants.CHANNEL_BATTERY_LEVEL, new StringType(batteryLevel));

            BigInteger fillLevelPercent = object.get(OilFoxBindingConstants.OILFOX_FILL_LEVEL_PERCENT)
                    .getAsBigInteger();
            logger.debug("onOilFoxRefresh(): hwid {}: fillLevelPercent {}", deviceHWID, fillLevelPercent);
            this.updateState(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_PERCENT,
                    DecimalType.valueOf(fillLevelPercent.toString()));

            String quantityUnit = object.get(OilFoxBindingConstants.OILFOX_QUANTITY_UNIT).getAsString();
            logger.debug("onOilFoxRefresh(): hwid {}: quantityUnit {}", deviceHWID, quantityUnit);
            this.updateState(OilFoxBindingConstants.CHANNEL_QUANTITY_UNIT, new StringType(quantityUnit));

            BigInteger fillLevelQuantity = object.get(OilFoxBindingConstants.OILFOX_FILL_LEVEL_QUANTITY)
                    .getAsBigInteger();
            if ("L".equals(quantityUnit)) {
                logger.debug("onOilFoxRefresh(): hwid {}: fillLevelQuantity {} L", deviceHWID, fillLevelQuantity);
                this.updateState(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_QUANTITY,
                        new QuantityType<>(DecimalType.valueOf(fillLevelQuantity.toString()), Units.LITRE));
            } else {
                logger.debug("onOilFoxRefresh(): hwid {}: fillLevelQuantity {} Kg", deviceHWID, fillLevelQuantity);
                this.updateState(OilFoxBindingConstants.CHANNEL_FILL_LEVEL_QUANTITY,
                        new QuantityType<>(DecimalType.valueOf(fillLevelQuantity.toString()), SIUnits.KILOGRAM));
            }
            updateStatus(ThingStatus.ONLINE);

            // schedule additional refresh to time 5 minutes after next metering
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));
            ZonedDateTime dateTimeWithZoneOffset = ZonedDateTime.parse(nextMeteringAt, formatter);
            LocalDateTime nextDeviceRefresh = LocalDateTime.ofInstant(dateTimeWithZoneOffset.toInstant(),
                    ZoneId.systemDefault());
            logger.debug("onOilFoxRefresh(): hwid {}: device metering in: last {} minutes, next {} minutes", deviceHWID,
                    MINUTES.between(LocalDateTime.now(), lastDeviceRefresh),
                    MINUTES.between(LocalDateTime.now(), nextDeviceRefresh));

            // calculate next additional refresh schedule, add 5 minutes to be save to get new metering
            long nextInMinutes = MINUTES.between(LocalDateTime.now(), nextDeviceRefresh) + 5;
            ScheduledFuture<?> localDeviceRefreshJob = this.deviceRefreshJob; // prevent race condition
            if (localDeviceRefreshJob != null) {
                // check if metering time has not changed
                if (MINUTES.between(lastDeviceRefresh, nextDeviceRefresh) == 0) {
                    logger.debug(
                            "onOilFoxRefresh(): hwid {}: device metering time unchanged, keep refresh schedule in {} minutes",
                            deviceHWID, nextInMinutes);
                    return;
                }
                // cleanup invalid additional refresh schedule after manual metering
                localDeviceRefreshJob.cancel(false); // false = does not cancel current running schedule
            }

            // add next additional refresh schedule
            logger.debug("onOilFoxRefresh(): hwid {}: add additional refresh schedule in {} minutes", deviceHWID,
                    nextInMinutes);
            deviceRefreshJob = scheduler.schedule(() -> {
                handleCommand(null, RefreshType.REFRESH);
            }, nextInMinutes, TimeUnit.MINUTES);
            lastDeviceRefresh = nextDeviceRefresh;
            return;
        }

        // Oilfox device HWID not found in API response
        updateStatus(ThingStatus.OFFLINE);
    }
}
