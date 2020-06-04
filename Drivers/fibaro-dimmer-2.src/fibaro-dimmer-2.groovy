/*
 *  Fibaro Single Switch 2
 *
 *  Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Fibaro Dimmer 2",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Switch Level"
		capability "ChangeLevel"
		capability "Energy Meter"
		capability "Power Meter"
		capability "PushableButton"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "Light"
		
		command "toggle"
		command "refresh"
		command "clearError"
		attribute "syncStatus", "string"
		attribute "errorMode", "string"

		fingerprint deviceId: "4096" , inClusters: "0x5E,0x20,0x86,0x72,0x26,0x5A,0x59,0x85,0x73,0x98,0x7A,0x56,0x70,0x31,0x32,0x8E,0x60,0x75,0x71,0x27,0x22", mfr: "0271", prod: "0258", deviceJoinName: "Fibaro Dimmer 2"
		fingerprint deviceId: "4096" , inClusters: "0x5E,0x20,0x86,0x72,0x26,0x5A,0x59,0x85,0x73,0x7A,0x56,0x70,0x31,0x32,0x8E,0x60,0x75,0x71,0x27,0x22", mfr: "0271", prod: "0258", deviceJoinName: "Fibaro Dimmer 2"
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

def startLevelChange(direction){ secureCmd(zwave.switchMultilevelV1.switchMultilevelStartLevelChange(upDown: (direction == "down")? 1 : 0, ignoreStartLevel: 1, startLevel: 0)) }

def stopLevelChange(){ secureCmd(zwave.switchMultilevelV1.switchMultilevelStopLevelChange()) }

def setLevel(level, rate = null ) { 
	if (rate == null) {
		secureCmd(zwave.basicV1.basicSet(value: (level > 0) ? level-1 : 0)) 
	} else {
		secureCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (level > 0) ? level-1 : 0, dimmingDuration: rate)) 
	}
}

def refresh() {
	def cmds = []
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet())
	delayBetween(cmds,500)
}

def clearError() { sendEvent(name: "errorMode", value: "clear") }

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

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	logging "debug", "SensorMultilevelReport value, ${cmd.scaledSensorValue} sensorType: ${cmd.sensorType}"
	if ( cmd.sensorType == 4 ) { sendEvent(name: "power", value: cmd.scaledSensorValue, unit: "W") }
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd ) {
	logging "debug", "MeterReport value: ${cmd.scaledMeterValue} scale: ${cmd.scale}"
	switch (cmd.scale) {
		case 0: sendEvent([name: "energy", value: cmd.scaledMeterValue, unit: "kWh"]); break
		case 2: sendEvent([name: "power", value: cmd.scaledMeterValue, unit: "W"]); break
	}
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
	logging "debug", "SwitchMultilevelReport received, value: ${cmd.value}"
	sendEvent(name: "switch", value: (cmd.value > 0) ? "on" : "off")
	sendEvent(name: "level", value: (cmd.value > 0) ? cmd.value+1 : 0) 
}

def zwaveEvent(hubitat.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
	logging "debug", "CentralSceneNotification: ${cmd}"
	def String action
	def Integer button
	switch (cmd.sceneNumber as Integer) {
		case [10,11,16]: action = "pushed"; button = 1; break
		case 14: action = "doubleTapped"; button = 1; break
		case [20,21,26]: action = "pushed"; button = 2; break
		case 24: action = "doubleTapped"; button = 2; break
		case 25: action = "pushed"; button = 3; break
		case 12: action = "held"; button = 1; break
		case 22: action = "held"; button = 2; break
		case 13: action = "released"; button = 1; break
		case 23: action = "released"; button = 2; break
	}
	
	description = "button ${button} was ${action}" + ( (button == 3 )? " ( button 2 trippletap )" : "" )
	
	logging "info", description
	sendEvent(name:action, value:button, descriptionText: description, isStateChange:true, type:type)
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
	logging "debug", "NotificationReport received for ${cmd.event}, parameter value: ${cmd.eventParameter[0]}"
	switch (cmd.notificationType) {
		case 4:
			switch (cmd.event) {
				case 0: sendEvent(name: "errorMode", value: "clear"); break;
				case [1,2]: sendEvent(name: "errorMode", value: "overheat"); break;
			}; break;
		case 8:
			switch (cmd.event) {
				case 0: sendEvent(name: "errorMode", value: "clear"); break;
				case 4: sendEvent(name: "errorMode", value: "surge"); break;
				case 5: sendEvent(name: "errorMode", value: "voltage drop"); break;
				case 6: sendEvent(name: "errorMode", value: "overcurrent"); break;
				case 8: sendEvent(name: "errorMode", value: "overload"); break;
				case 9: sendEvent(name: "errorMode", value: "load error"); break;
			}; break;
		case 9:
			switch (cmd.event) {
				case 0: sendEvent(name: "errorMode", value: "clear"); break;
				case [1,3]: sendEvent(name: "errorMode", value: "hardware"); break;
			}; break;
		default: logging "warn", "$Unknown zwaveAlarmType: ${cmd.zwaveAlarmType}"
	}
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
	if (paramData) {
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
			if (!device.currentValue("syncStatus").contains("Rejected")) { sendEvent(name: "syncStatus", value: "Wrong value on param ${paramData.num}") }
		} else {
			logging "debug", "Parameter ${paramData.key} new value"
			device.updateSetting("${paramData.key}", [value:receivedVal, type: paramData.type])
			state."${paramData.key}" = receivedVal
		}  
	} else {
		logging "debug", "Received parameter ${cmd.parameterNumber} data, value is ${cmd.scaledConfigurationValue}. Ignoring" //ignore unused parameters
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
		if (!device.currentValue("syncStatus").contains("Rejected")) { sendEvent(name: "syncStatus", value: "Complete") }
	}
}

/*
#############################
## Configure, Updated etc. ##
#############################
*/
void configure(){
	logging "debug", "configure"
	if (device.currentValue("numberOfButtons") != 3) { sendEvent(name: "numberOfButtons", value: 3) }
}

def updated() {
	logging "debug", "updated"
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
@Field static Map commandClassVersions = [0x5E: 1, 0x86: 1, 0x72: 2, 0x59: 1, 0x73: 1, 0x22: 1, 0x31: 5, 0x32: 3, 0x71: 3, 0x56: 1, 0x98: 1, 0x7A: 2, 0x20: 1, 0x5A: 1, 0x85: 2, 0x26: 3, 0x8E: 2, 0x60: 3, 0x70: 2, 0x75: 2, 0x27: 1]

@Field static parameterMap = [
		[key: "minBrightnessLvl", num: 1, size: 1, type: "number", def: "1", min: 1, max: 98 , title: "Minimum brightness level"],
		[key: "maxBrightnessLvl", num: 2, size: 1, type: "number", def: "99", min: 2, max: 99 , title: "Maximum brightness level "],
		[key: "autoStepSize", num: 5, size: 1, type: "number", def: "1", min: 1, max: 99 , title: "Automatic control - dimming step size"],
		[key: "autoStepTime", num: 6, size: 2, type: "number", def: "1", min: 0, max: 255 , title: "Automatic control - time of a dimming step"],
		[key: "manualStepSize", num: 7, size: 1, type: "number", def: "1", min: 1, max: 99 , title: "Manual control - dimming step size"],
		[key: "manualStepTime", num: 8, size: 2, type: "number", def: "5", min: 0, max: 255 , title: "Manual control - time of a dimming step"],
		[key: "powerFailure", num: 9, size: 1, type: "enum", options: [
				0: "returns to „off” position",
				1: "restores its state before power failure",
			], def: "1", min: 0, max: 1 , title: "State of the device after a power failure"],
		[key: "autoOff", num: 10, size: 2, type: "number", def: 0, min: 0, max: 32767 , title: "Timer functionality (auto - off)"],
		[key: "autoCalibration", num: 13, size: 1, type: "enum", options: [
				0: "readout",
				1: "force auto-calibration of the load without FIBARO Bypass 2",
				2: "force auto-calibration of the load with FIBARO Bypass 2"
			], def: "0", min: 0, max: 2 , title: "Force auto-calibration"],
		[key: "switchType", num: 20, size: 1, type: "enum", options: [
				0: "momentary switch",
				1: "toggle switch",
				2: "roller blind switch"
			], def: "0", min: 0, max: 2 , title: "Switch type"],
		[key: "threeWaySwitch", num: 26, size: 1, type: "enum", options: [
				0: "disabled",
				1: "enabled"
			], def: "0", min: 0, max: 1 , title: "The function of 3-way switch"],
		[key: "sceneActivation", num: 28, size: 1, type: "enum", options: [
				0: "disabled",
				1: "enabled"
			], def: "0", min: 0, max: 1 , title: "Scene activation functionality"],
		[key: "loadControllMode", num: 30, size: 1, type: "enum", options: [
				0: "forced leading edge control",
				1: "forced trailing edge control",
				2: "control mode selected automatically (based on auto-calibration)"
			], def: "2", min: 0, max: 2 , title: "Load control mode"],
		[key: "levelCorrection", num: 38, size: 2, type: "number", def: 255, min: 0, max: 255 , title: "Brightness level correction for flickering loads"],
		[key: "activePowerReports", num: 50, size: 1, type: "number", def: 10, min: 0, max: 100 , title: "Active power and energy reports"],
		[key: "periodicPowerReports", num: 52, size: 2, type: "number", def: 3600, min: 0, max: 32767 , title: "Periodic active power and energy reports"],
		[key: "energyReports", num: 53, size: 2, type: "number", def: 10, min: 0, max: 255 , title: "Energy reports"],
		[key: "selfMeasurement ", num: 54, size: 1, type: "enum", options: [
				0: "inactive",
				1: "active",
			], def: "0", min: 0, max: 1 , title: "Self-measurement"]
	]