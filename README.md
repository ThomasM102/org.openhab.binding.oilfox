# OilFox Binding

Binding for [OilFox](https://foxinsights.ai/) smart remote tank monitoring system.

This binding allows you to check the fuel level in your tank.

## Supported Things


| Thing type               | Name           |
|--------------------------|----------------|
| account                  | OilFox Account |
| device                   | OilFox Device  |


- `account`: Connect openHAB to OilFox cloud server via [FoxInsights Customer API](https://github.com/foxinsights/customer-api)
- `device` : A OilFox tank fill level measuring hardware device

## Discovery

An account must be specified in the OilFox account configuration, all OilFox devices for an account are discovered automatically.

## Binding Configuration

There are several settings for an account:

| Name     | Required |    Default    | Description                             |
|----------|----------|---------------|-----------------------------------------|
| address  |   yes    | api.oilfox.io | OilFox Cloud server address             |
| email    |   yes    |               | Email registered on the OilFox Cloud    |
| password |   yes    |               | Password registered on the OilFox Cloud |
| refresh  |   yes    |             6 | refresh interval in hours               |


## Thing Configuration

### `device` Thing Configuration

| Name     | Required |    Default    | Description                            |
|----------|----------|---------------|----------------------------------------|
| hwid     |   yes    |               | OilFox device hardware address         |

## Channels

|      Channel      | Type     | Read/Write | Description                                 |
|-------------------|----------|------------|---------------------------------------------|
| hwid              | String   |  readonly  | hardware ID of the device                   |
| currentMeteringAt | DateTime |  readonly  | RFC3339 timestamp                           |
| nextMeteringAt    | DateTime |  readonly  | RFC3339 timestamp                           |
| daysReach         | Number   |  readonly  | estimated days until the storage runs empty |
| batteryLevel      | String   |  readonly  | enum of the battery level, see below        |
| fillLevelPercent  | Number   |  readonly  | fill level in %, 0-100                      |
| fillLevelQuantity | Number   |  readonly  | fill level in `kg` or `L`                   |
| quantityUnit      | String   |  readonly  | unit of the fill level: `kg` or `L`         |

### Enum batteryLevel

| name     | description            |
|----------|------------------------|
| FULL     | Full battery level     |
| GOOD     | Good battery level     |
| MEDIUM   | Medium battery level   |
| WARNING  | Low battery level      |
| CRITICAL | Critical battery level |


## Full Example

### Thing Configuration

```java
Bridge oilfox:account:myaccount [ email="my-email@provider.com", password="my-password", refresh=6 ]
Thing oilfox:device:myaccount:mydevice "OilFox Device" (oilfox:account:myaccount) @ "Oiltank Room" [ hwid="XX123456789" ]
```

### Item Configuration

```java
DateTime Current_Metering_At "current metering at" {channel="oilfox:device:myaccount:mydevice:current-metering-at"}
DateTime Next_Metering_At "next metering at" {channel="oilfox:device:myaccount:mydevice:next-metering-at"}
Number Days_Reach "days reach" {channel="oilfox:device:myaccount:mydevice:days-reach", stateDescription=" "[ pattern="%.0f days" ]}
String Battery_Level "battery level" {channel="oilfox:device:myaccount:mydevice:battery-level"}
Number Fill_Level_Percent "fill level percent" {channel="oilfox:device:myaccount:mydevice:fill-level-percent"}
Number Fill_Level_Quantity "fill level quantity" {channel="oilfox:device:myaccount:mydevice:fill-level-quantity"}
String Quantity_Unit "quantity unit" {channel="oilfox:device:myaccount:mydevice:quantity-unit"}
```
