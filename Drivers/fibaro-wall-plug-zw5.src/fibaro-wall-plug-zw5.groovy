/*
 *  Fibaro Single Switch 2
 *
 *  Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Fibaro Wall Plug zw5",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Energy Meter"
		capability "Power Meter"
		capability "Refresh"

		command "toggle"
		command "resetEnergy"
		
		attribute "syncStatus", "string"

		fingerprint mfr:"010F", prod:"0602", deviceId:"1001", inClusters:"0x5E,0x22,0x85,0x59,0x70,0x56,0x5A,0x7A,0x72,0x32,0x8E,0x71,0x73,0x98,0x31,0x25,0x86" 
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

def on() { secureCmd(zwave.basicV1.basicSet(value: 255)) }

def off() { secureCmd(zwave.basicV1.basicSet(value: 0)) }

def toggle() { device.currentValue("switch") != "on" ? on():off() }

def refresh() { 
	def cmds = []
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 2))
	delayBetween(cmds,1000)
}

def resetEnergy() {
	def cmds = []
	cmds << secureCmd(zwave.meterV3.meterReset())
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	delayBetween(cmds,1000)
}

/*
###################
## Encapsulation ##
###################
*/
def secureCmd(cmd) { //zwave secure encapsulation
	logging "debug", "secureCmd: ${cmd}"
	if (getDataValue("zwaveSecurePairingComplete") == "true") {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		return cmd.format()
	}
}

/*
######################
## Parse and Events ##
######################
*/
void parse(String description){
	logging "debug", "parse: ${description}"
	hubitat.zwave.Command cmd = zwave.parse(description,commandClassVersions)
	if (cmd) { zwaveEvent(cmd) }
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapCmd = cmd.encapsulatedCommand()
	def result = []
	if (encapCmd) {
		logging "debug", "SecurityMessageEncapsulation: ${cmd}"
		zwaveEvent(encapCmd)
	} else {
	   logging "warn", "Unable to extract secure cmd from: ${cmd}"
	}
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) { logging "debug", "(ignored) BasicReport: ${cmd}" /*ignore*/ }

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) { logging "debug", "(ignored) BasicSet: ${cmd}" /*ignore*/ }

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	logging "debug", "SwitchBinaryReport value: ${cmd.value}"
	logging "info", "${(cmd.value == 0 ) ? "off": "on"}"
	sendEvent([name: "switch", value: (cmd.value == 0 ) ? "off": "on"])
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd ) {
	logging "debug", "MeterReport value: ${cmd.scaledMeterValue} scale: ${cmd.scale}"
	switch (cmd.scale) {
		case 0: sendEvent([name: "energy", value: cmd.scaledMeterValue, unit: "kWh"]); break
		case 2: sendEvent([name: "power", value: cmd.scaledMeterValue, unit: "W"]); break
	}
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	logging "debug", "SensorMultilevelReport value: ${cmd.scaledSensorValue} scale: ${cmd.scale}"
	if (cmd.sensorType == 4) { sendEvent([name: "power", value: cmd.scaledSensorValue, unit: "W"]) }
}


void zwaveEvent(hubitat.zwave.Command cmd){
	logging "warn", "unhandled zwaveEvent: ${cmd}"
}

/*
####################
## Parameter Sync ##
####################
*/
def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
	logging "debug", "ConfigurationReport: ${cmd}"
	def paramData = parameterMap.find( {it.num == cmd.parameterNumber } )
	def previousVal = state."${paramData.key}".toString()
	def expectedVal = this["${paramData.key}"].toString()
	def receivedVal = cmd.scaledConfigurationValue.toString()
	
	logging "info", "Parameter ${paramData.key} value is ${receivedVal} expected ${expectedVal}"
	if (previousVal == receivedVal && expectedVal == receivedVal) {
		//ignore
	} else if (expectedVal == receivedVal) {
		logging "debug", "Parameter ${paramData.key} as expected"
		state."${paramData.key}" = receivedVal
		syncNext()
	} else if (previousVal == receivedVal) {
		logging "debug", "Parameter ${paramData.key} not changed - sync failed"
		if (device.currentValue("syncStatus").contains("In progres")) { sendEvent(name: "syncStatus", value: "Wrong value on param ${paramData.num}") }
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
			sendEvent(name: "syncStatus", value: "Rejected Request for parameter: ${param.num}")
			break
		}
	}
}

private syncNext() {
	logging "debug", "syncNext()"  
	def cmds = []
	for ( param in parameterMap ) {
		if ( this["$param.key"] != null && state."$param.key".toString() != this["$param.key"].toString() ) {
			cmds << secureCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: this["$param.key"].toInteger(), parameterNumber: param.num, size: param.size))
			cmds << secureCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
			sendEvent(name: "syncStatus", value: "In progress (parameter: ${param.num})")
			break
		} 
	}
	if (cmds) { 
		sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE))
	} else {
		logging "info", "Sync Complete"  
		if (device.currentValue("syncStatus").contains("In progress")) { sendEvent(name: "syncStatus", value: "Complete") }
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
	runIn(3,"syncNext")
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
@Field static Map commandClassVersions = [0x5E: 2, 0x22: 1, 0x59: 1, 0x56: 1, 0x7A: 1, 0x32: 3, 0x71: 1, 0x73: 1, 0x98: 1, 0x31: 5, 0x85: 2, 0x70: 2, 0x72: 2, 0x5A: 1, 0x8E: 2, 0x25: 1, 0x86: 2]

@Field static parameterMap = [
	[key: "alwaysActive", num: 1, size: 1, type: "enum", options: [0: "function inactive", 1: "function activated"], def: "0", title: "Always On function"],
	[key: "restoreState", num: 2, size: 1, type: "enum", options: [0: "device remains switched off", 1: "device restores the state"], def: "1", title: "Restore state after power failure"],
	[key: "overloadSafety", num: 3, size: 2, type: "number", def: 0, min: 0, max: 30000 , title: "Overload safety switch"],
	[key: "priorityPowerReports", num: 10, size: 1, type: "number", def: 80, min: 1, max: 100, title: "High priority power report"], 
	[key: "standardPowerReports", num: 11, size: 1, type: "number", def: 15, min: 1, max: 100, title: "Standard power reports"], 
	[key: "powerReportFrequency", num: 12, size: 2, type: "number", def: 30, min: 5, max: 600, title: "Power reporting interval"],
	[key: "powerEnergyThreshold", num: 13, size: 2, type: "number", def: 10, min: 0, max: 500, title: "Energy reporting threshold"],
	[key: "periodicReports", num: 14, size: 2, type: "number", def: 3600, min: 0, max: 32400, title: "Periodic power and energy reports"], 
	[key: "selfPowerReports", num: 15, size: 1, type: "enum", options: [0:"function inactive",1:"function activated"], def: 0, min: 0, max: 1, title: "Measuring energy consumed by the Wall Plug itself"], 
	[key: "periodicReports", num: 40, size: 2, type: "number", def: 25000, min: 1000, max: 30000, title: "Power load for violet colour"], 
	[key: "ringColorOn", num: 41, size: 1, type: "enum", options: [
		0: "Off",
		1: "Load based - continuous", 
		2: "Load based - steps", 
		3: "White", 
		4: "Red", 
		5: "Green", 
		6: "Blue", 
		7: "Yellow", 
		8: "Cyan", 
		9: "Magenta"
		], def: "1", title: "Ring LED color when on"],
	[key: "ringColorOff", num: 42, size: 1, type: "enum", options: [
		0: "Off",
		1: "Last measured power",  
		3: "White", 
		4: "Red", 
		5: "Green", 
		6: "Blue", 
		7: "Yellow", 
		8: "Cyan", 
		9: "Magenta"
		], def: "0", title: "Ring LED color when off"]
]
