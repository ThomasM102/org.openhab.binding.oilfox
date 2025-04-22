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
package org.openhab.binding.oilfox;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link OilFoxBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Roland Moser - Initial contribution
 */
@NonNullByDefault
public class OilFoxBindingConstants {

    private static final String BINDING_ID = "oilfox";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "bridge");
    public static final ThingTypeUID THING_TYPE_OILFOX = new ThingTypeUID(BINDING_ID, "device");

    // List of all Channel ids
    public static final String CHANNEL_CURRENT_METERING_AT = "currentMeteringAt";
    public static final String CHANNEL_NEXT_METERING_AT = "nextMeteringAt";
    public static final String CHANNEL_DAYS_REACH = "daysReach";
    public static final String CHANNEL_BATTERY_LEVEL = "batteryLevel";
    public static final String CHANNEL_FILL_LEVEL_PERCENT = "fillLevelPercent";
    public static final String CHANNEL_FILL_LEVEL_QUANTITY = "fillLevelQuantity";
    public static final String CHANNEL_QUANTITY_UNIT = "quantityUnit";

    // List of all supported thing types
    public static final Set<ThingTypeUID> SUPPORTED_DEVICE_TYPES = Collections.singleton(THING_TYPE_OILFOX);
    public static final Set<ThingTypeUID> SUPPORTED_BRIDGE_TYPES = Collections.singleton(THING_TYPE_BRIDGE);
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Stream.of(THING_TYPE_OILFOX, THING_TYPE_BRIDGE)
            .collect(Collectors.toSet());

    // List of all Properties
    public static final String PROPERTY_HWID = "hwid";
}
