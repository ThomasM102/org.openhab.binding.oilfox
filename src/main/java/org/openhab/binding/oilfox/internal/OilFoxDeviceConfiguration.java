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
package org.openhab.binding.oilfox.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link OilFoxBridgeConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Roland Moser - Initial contribution
 * @author Thomas M. - adapted to new FoxInsights Customer API
 */

@NonNullByDefault
public class OilFoxDeviceConfiguration {
    public @Nullable String hwid;
}
