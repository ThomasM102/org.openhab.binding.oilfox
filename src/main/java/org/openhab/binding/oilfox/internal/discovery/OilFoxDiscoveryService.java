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
package org.openhab.binding.oilfox.internal.discovery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.oilfox.OilFoxBindingConstants;
import org.openhab.binding.oilfox.handler.OilFoxBridgeHandler;
import org.openhab.binding.oilfox.handler.OilFoxStatusListener;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;

/**
 *
 * @author Roland Moser - Initial contribution
 */

@NonNullByDefault
public class OilFoxDiscoveryService extends AbstractDiscoveryService implements OilFoxStatusListener {
    private final Logger logger = LoggerFactory.getLogger(OilFoxDiscoveryService.class);

    private static final int SEARCH_TIME = 60;

    private @Nullable ServiceRegistration<?> reg = null;

    private OilFoxBridgeHandler oilFoxBridgeHandler;

    public OilFoxDiscoveryService(OilFoxBridgeHandler oilFoxBridgeHandler) {
        super(OilFoxBindingConstants.SUPPORTED_DEVICE_TYPES, SEARCH_TIME);
        this.oilFoxBridgeHandler = oilFoxBridgeHandler;
    }

    public void activate() {
        oilFoxBridgeHandler.registerOilFoxStatusListener(this);
    }

    @Override
    public void deactivate() {
        oilFoxBridgeHandler.unregisterOilFoxStatusListener(this);
    }

    @Override
    protected void startScan() {
        try {
            oilFoxBridgeHandler.getAllDevices();
        } catch (MalformedURLException e) {
            logger.error("Exception occurred during execution: {}", e.getMessage(), e);
        } catch (InterruptedIOException e) {
            logger.error("Exception occurred during execution: {}", e.getMessage(), e);

        } catch (IOException e) {
            logger.error("Exception occurred during execution: {}", e.getMessage(), e);
        }
    }

    public void start(BundleContext bundleContext) {
        if (reg != null) {
            return;
        }
        reg = bundleContext.registerService(DiscoveryService.class.getName(), this, new Hashtable<>());
    }

    public void stop() {
        ServiceRegistration<?> localReg = this.reg; // prevent race condition
        if (localReg != null) {
            localReg.unregister();
        }
        reg = null;
    }

    @Override
    public void onOilFoxRemoved(ThingUID bridge, String hwid) {
        logger.debug("onOilFoxRemoved(): bridge {}, hwid {}", bridge, hwid);
    }

    @Override
    public void onOilFoxAdded(ThingUID bridge, String hwid) {
        logger.debug("onOilFoxAdded(): bridge {}, hwid {}", bridge, hwid);
        String label = "Oilfox " + hwid;

        ThingTypeUID uid = OilFoxBindingConstants.THING_TYPE_OILFOX;
        ThingUID thingUID = new ThingUID(uid, bridge, hwid); // thingUID can not be null

        Map<String, Object> properties = new HashMap<>(1);
        properties.put(OilFoxBindingConstants.PROPERTY_HWID, hwid);

        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withLabel(label).withBridge(bridge)
                .withProperties(properties).withRepresentationProperty(hwid).build();
        thingDiscovered(discoveryResult);
    }

    @Override
    public @Nullable String getHWID() {
        return null;
    }

    @Override
    public void onOilFoxRefresh(JsonArray devices) {
    }
}
