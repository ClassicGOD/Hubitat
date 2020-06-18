/*
 *  Fibaro Smoke Sensor
 *
 *  Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Fibaro Smoke Sensor",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Smoke Detector"
		capability "Tamper Alert"
		capability "Temperature Measurement"
		capability "Battery"
		
		attribute "syncStatus", "string"
		attribute "tempAlarm", "enum", ["clear", "detected"]

		fingerprint  mfr:"010F", prod:"0C02", deviceId:"1002", inClusters:"0x5E,0x20,0x86,0x72,0x5A,0x59,0x85,0x73,0x84,0x80,0x71,0x56,0x70,0x31,0x8E,0x22,0x9C,0x98,0x7A" 
	}
	
	preferences {
		parameterMap.each {
			input (
				name: it.key,
				title: "${it.num}. ${it.title}",
				type: it.type,
				options: it.options,
				range: (it.min != null && it.max != null) ? "${it.min}..${it.max}" : null,
				defaultValue: it.def,
				required: false
			)
		}
		
		input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: true
		input name: "infoEnable", type: "bool", title: "Enable info logging", defaultValue: true
	}
}

/*
###################
## Encapsulation ##
###################
*/
def encapCmd(hubitat.zwave.Command cmd, Integer ep=null) { //for all your encap needs :D
	logging "debug", "encapCmd: ${cmd} ep: ${ep}"
	if (ep) cmd = zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:ep).encapsulate(cmd) 
	if (getDataValue("zwaveSecurePairingComplete") == "true") cmd = zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd) 
	return cmd.format()
}

/*
######################
## Parse and Events ##
######################
*/
void parse(String description){
	logging "debug", "parse: ${description}"
	hubitat.zwave.Command cmd = zwave.parse(description,commandClassVersions)
	if (cmd) zwaveEvent(cmd)
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	logging "debug", "SecurityMessageEncapsulation: ${cmd}"
	def encapCmd = cmd.encapsulatedCommand()
	if (encapCmd) zwaveEvent(encapCmd)
	else logging "warn", "Unable to extract secure cmd from: ${cmd}"
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
	logging "debug", "Crc16Encap: ${cmd}"
	def encapCmd = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data, commandClassVersions[cmd.commandClass as Integer]?: 1)
	if (encapCmd) zwaveEvent(encapCmd)
	else logging "warn", "Unable to extract Crc16Encap cmd from: ${cmd}"
}

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	logging "debug", "MultiChannelCmdEncap: ${cmd}"
	def encapCmd = cmd.encapsulatedCommand()
	if (encapCmd) zwaveEvent(encapCmd, cmd.sourceEndPoint as Integer)
	else logging "warn", "Unable to extract multi channel cmd from: ${cmd}"
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
	logging "debug", "BatteryReport: ${cmd}"
	logging "info", "battery is $cmd.batteryLevel%"
	sendEvent(name: "battery", value: cmd.batteryLevel)
}

def zwaveEvent(hubitat.zwave.commands.notificationv5.NotificationReport cmd) {
	logging "debug", "NotificationReport: ${cmd}"
	logging "info", "NotificationReport type: ${cmd.notificationType}, event: ${cmd.event}"
	switch (cmd.notificationType) {
		case 1: sendEvent([name: "smoke", value: (cmd.event == 0) ? "clear" : (cmd.event == 3) ? "tested" : "detected"]); break;
		case 4: sendEvent([name: "tempAlarm", value: (cmd.event == 0) ? "clear" : "detected" ]); break;
		case 7: sendEvent([name: "tamper", value: (cmd.event == 0) ? "clear" : "detected" ]); break;
		default: logging "warn", "Unknown zwaveAlarm type: ${cmd.notificationType}, event: ${cmd.event}"
	}
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	logging "debug", "SensorMultilevelReport: ${cmd}"
	switch (cmd.sensorType) {
		case 1: sendEvent([name: "temperature", unit: (cmd.scale == 1) ? "F" : "C" , value: cmd.scaledSensorValue ]); break;
		default: logging "warn", "Unknown sensorType: ${cmd.sensorType}";
	}
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=null){ logging "warn", "unhandled zwaveEvent: ${cmd} ep: ${ep}" }

/*
####################
## Parameter Sync ##
####################
*/
def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd) {
	logging "debug", "WakeUpNotification: ${cmd}"
	logging "info", "woke up"

	sendHubCommand(new hubitat.device.HubAction(encapCmd(zwave.batteryV1.batteryGet()), hubitat.device.Protocol.ZWAVE))

	if (device.currentValue("syncStatus") != "complete") {
		sendEvent([name: "syncStatus", value: "in progress"])
		syncNext()
	}
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
	logging "debug", "ConfigurationReport: ${cmd}"
	def paramData = parameterMap.find( {it.num == cmd.parameterNumber } )
	def previousVal = state."${paramData.key}".toString()
	def expectedVal = this["${paramData.key}"].toString()
	def receivedVal = cmd.scaledConfigurationValue.toString()
	
	logging "info", "Parameter ${paramData.key} value is ${receivedVal} expected ${expectedVal}"
	if (previousVal == receivedVal && expectedVal == receivedVal) { //ignore
	} else if (expectedVal == receivedVal) {
		logging "debug", "Parameter ${paramData.key} as expected"
		state."${paramData.key}" = receivedVal
		syncNext()
	} else if (previousVal == receivedVal) {
		logging "debug", "Parameter ${paramData.key} not changed - sync failed"
		if (device.currentValue("syncStatus").contains("in progres")) { sendEvent(name: "syncStatus", value: "wrong value on param ${paramData.num}") }
	} else {
		logging "debug", "Parameter ${paramData.key} new value"
		device.updateSetting("${paramData.key}", [value:receivedVal, type: paramData.type])
		state."${paramData.key}" = receivedVal
	}
}

def zwaveEvent(hubitat.zwave.commands.applicationstatusv1.ApplicationRejectedRequest cmd) {
	logging "warn", "Rejected Configuration!"
	for ( param in parameterMap ) {
		if (state."$param.key".toString() != this["$param.key"].toString()) {
			sendEvent(name: "syncStatus", value: "rejected Request for parameter: ${param.num}")
			break
		}
	}
}

private syncNext() {
	logging "debug", "syncNext()"
	def cmds = []
	for ( param in parameterMap ) {
		if ( this["$param.key"] != null && state."$param.key".toString() != this["$param.key"].toString() ) {
			cmds << encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: this["$param.key"].toInteger(), parameterNumber: param.num, size: param.size))
			cmds << encapCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
			sendEvent([name: "syncStatus", value: "in progress (parameter: ${param.num})"])
			break
		} 
	}
	if (cmds) { 
		sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE))
	} else {
		logging "info", "Sync Complete"
		if (device.currentValue("syncStatus").contains("in progress")) { sendEvent(name: "syncStatus", value: "complete") }
	}
}

/*
#############################
## Configure, Updated etc. ##
#############################
*/

def updated() {
	logging "debug", "updated"
	if (!device.currentValue("syncStatus")) { state.clear() }
	
	for ( param in parameterMap ) {
		if ( this["$param.key"] != null && state."$param.key".toString() != this["$param.key"].toString() ) {
			sendEvent(name: "syncStatus", value: "pending")
			break
		} 
	}
}

/*
###########
## Other ##
###########
*/
void logging(String type, String text) { //centralized logging
	text = "${device.displayName}: " + text
	if ((debugEnable || debugEnable == null) && type == "debug") log.debug text
	if ((infoEnable || infoEnable == null) && type == "info") log.info text
	if (type == "warn") log.warn text
}

/*
###################
## Device config ##
###################
*/
@Field static Map commandClassVersions = [0x5E: 2,0x20: 1,0x86: 2,0x72: 2,0x5A: 1,0x59: 1,0x85: 2,0x73: 1,0x84: 2,0x80: 1,0x71: 5,0x56: 1,0x70: 1,0x31: 8,0x8E: 2,0x22: 1,0x9C: 1,0x98: 1,0x7A: 3]

@Field static parameterMap = [
	[key: "sensitivity", title: "Smoke Sensor sensitivity", type: "enum", options: [1: "High", 2: "Medium", 3: "Low"], num: 1, size: 1, def: 2, min: 1, max: 3 ],
	[key: "notifications", title: "Z-Wave notifications status", type: "enum", options: [0: "all notifications disabled", 1: "case opening notification", 2: "temperature threshold notification", 3: "both"], num: 2, size: 1, def: 0, min: 0, max: 3 ],
	[key: "visualIndicator", title: "Visual indicator notifications status", type: "enum", options: [
		0: "disabled",
		1: "case opening",
		2: "temperature treshold",
		3: "case opening & temperature treshold",
		4: "low Z-Wave range",
		5: "case opening & low Z-Wave range",
		6: "temperature treshold & low Z-Wave range",
		7: "all 3" ], num: 3, size: 1, def: 0, min: 0, max: 7 ],
	[key: "soundNotification", title: "Sound notifications status", type: "enum", options: [
		0: "disabled",
		1: "case opening",
		2: "temperature treshold",
		3: "case opening & temperature treshold",
		4: "low Z-Wave range",
		5: "case opening & low Z-Wave range",
		6: "temperature treshold & low Z-Wave range",
		7: "all 3" ], num: 4, size: 1, def: 0, min: 0, max: 7 ],
	[key: "tempInterval", title: "Temperature report interval", type: "number", num: 20, size: 2, def: 1, min: 0, max: 8640 ],
	[key: "tempHysteresis", title: "Temperature report hysteresis", type: "number", num: 21, size: 1, def: 10, min: 1, max: 100 ],
	[key: "tempThreshold", title: "Temperature threshold", type: "number", num: 30, size: 1, def: 55, min: 1, max: 100 ],
	[key: "tempSignaling", title: "Excess temperature signaling interval", type: "number", num: 31, size: 2, def: 1, min: 1, max: 8640 ]
]
