/*
 *  Fibaro Double Switch 2
 *
 *  Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Fibaro Double Switch 2",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Energy Meter"
		capability "Power Meter"
		capability "PushableButton"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "Light"
		
		command "toggle"

		attribute "syncStatus", "string"

		fingerprint deviceId: "4096" , inClusters: "0x5E,0x86,0x72,0x25,0x5A,0x59,0x85,0x73,0x56,0x70,0x32,0x8E,0x60,0x22,0x75,0x71,0x98,0x7A,0x5B", mfr: "0271", prod: "0515", deviceJoinName: "Fibaro Double Switch 2"
		fingerprint deviceId: "4096" , inClusters: "0x5E,0x86,0x72,0x25,0x5A,0x59,0x85,0x73,0x56,0x70,0x32,0x8E,0x60,0x22,0x75,0x71,0x7A,0x5B", mfr: "0271", prod: "0515", deviceJoinName: "Fibaro Double Switch 2"
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

def on() { secureCmd(endpointCmd(zwave.basicV1.basicSet(value: 255),1)) }

def off() { secureCmd(endpointCmd(zwave.basicV1.basicSet(value: 0),1)) }

def toggle() { device.currentValue("switch") != "on" ? on():off() }

def childOn() { sendHubCommand(new hubitat.device.HubAction(secureCmd(endpointCmd(zwave.basicV1.basicSet(value: 255),2)), hubitat.device.Protocol.ZWAVE)) }

def childOff() { sendHubCommand(new hubitat.device.HubAction(secureCmd(endpointCmd(zwave.basicV1.basicSet(value: 0),2)), hubitat.device.Protocol.ZWAVE)) }

def childToggle() { getChildDevice("${device.deviceNetworkId}-2")?.currentValue("switch") != "on" ? childOn():childOff() }

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

def endpointCmd(cmd, ep) { //zwave MultiChannel Encap
	logging "debug", "endpointCmd: ${cmd}"
	return zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:ep).encapsulate(cmd)
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

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	def encapCmd = cmd.encapsulatedCommand()
	if (encapCmd) {
		logging "debug", "MultiChannelCmdEncap: ${cmd}"
		zwaveEvent(encapCmd, cmd.sourceEndPoint as Integer)
	} else {
	   logging "warn", "Unable to extract multi channel cmd from: ${cmd}"
	}
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, ep=null) { logging "debug", "(ignored) BasicReport: ${cmd} ep: ${ep}" /*ignore*/ }

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd, ep=null) { logging "debug", "(ignored) BasicSet: ${cmd} ep: ${ep}" /*ignore*/ }

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, ep=null) {
	logging "debug", "SwitchBinaryReport value: ${cmd.value} ep: $ep"
	logging "info", "relay ${ep} ${(cmd.value == 0 ) ? "off": "on"}";
	switch (ep) {
		case 1: sendEvent([name: "switch", value: (cmd.value == 0 ) ? "off": "on"]);  break
		case 2: getChildDevice("${device.deviceNetworkId}-2").sendEvent([name: "switch", value: (cmd.value == 0 ) ? "off": "on"]); break
	}
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd, ep=null) {
	logging "debug", "MeterReport value: ${cmd.scaledMeterValue} scale: ${cmd.scale} ep: $ep"
	if (ep==1) {
		switch (cmd.scale) {
			case 0: sendEvent([name: "energy", value: cmd.scaledMeterValue, unit: "kWh"]); break
			case 2: sendEvent([name: "power", value: cmd.scaledMeterValue, unit: "W"]); break
		}
	} else if (ep==2) {
		switch (cmd.scale) {
			case 0: getChildDevice("${device.deviceNetworkId}-2")?.sendEvent([name: "energy", value: cmd.scaledMeterValue, unit: "kWh"]); break
			case 2: getChildDevice("${device.deviceNetworkId}-2")?.sendEvent([name: "power", value: cmd.scaledMeterValue, unit: "W"]); break
		}
	}
}

def zwaveEvent(hubitat.zwave.commands.protectionv2.ProtectionReport cmd) {
	logging "debug", "ProtectionReport: ${cmd}"
	logging "info", "Protection set to: ${cmd.localProtectionState}"
	if (state.protection.value == cmd.localProtectionState) {
		state.protection.state = "synced"
	}
}

def zwaveEvent(hubitat.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
	logging "debug", "CentralSceneNotification: ${cmd}"
	def realButton = cmd.sceneNumber as Integer 
	def keyAttribute = cmd.keyAttributes as Integer
	def Integer mappedButton
	def String action
	def String description
	/*  buttons:
		1-2	Single Presses, Double Presses, Hold, Release
		3-4	Tripple Presses */
	mappedButton = realButton
	switch (keyAttribute) {
		case 0: action = "pushed"; break
		case 1: action = "released"; break
		case 2: action = "held"; break
		case 3: action = "doubleTapped"; break
		case 4: mappedButton = realButton + 2; action = "pushed"; break
	}
	
	description = "button ${mappedButton} was ${action}" + ( keyAttribute == 4 ? " ( button ${realButton} trippletap )" : "" )
   
	logging "info", description
	sendEvent(name:action, value:mappedButton, descriptionText: description, isStateChange:true, type:type)
}

def zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd) {
	logging "debug",  "MultiChannelAssociationReport: ${cmd}"
	def cmds = []
	if (cmd.groupingIdentifier == 1) {
		if (cmd.multiChannelNodeIds != [[nodeId:zwaveHubNodeId, bitAddress:0, endPointId:1]]) {
			logging "info", "${device.displayName} - incorrect MultiChannel Association for Group 1! nodeId: ${cmd.nodeId} will be changed to [0, ${zwaveHubNodeId}, 1]"
			cmds << secureCmd(zwave.multiChannelAssociationV2.multiChannelAssociationRemove(groupingIdentifier: 1))
			cmds << secureCmd(zwave.multiChannelAssociationV2.multiChannelAssociationSet(groupingIdentifier: 1, nodeId: [0,zwaveHubNodeId,1]))
		} else {
			logging "debug", "${device.displayName} - MultiChannel Association for Group 1 correct."
		}
	}  
	if (cmds) { sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE)) }
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=null){
	logging "warn", "unhandled zwaveEvent: ${cmd} ep: ${ep}"
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
void configure(){
	logging "debug", "configure"
	if (device.currentValue("numberOfButtons") != 4) { sendEvent(name: "numberOfButtons", value: 4) }
}

def updated() {
	logging "debug", "updated"
	if (!childDevices) { createChildDevices() } 
	if (device.currentValue("numberOfButtons") != 4) { sendEvent(name: "numberOfButtons", value: 4) }
	
	runIn(3,"syncNext")
	
	return response(secureCmd(zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: 1))) //verify if group 1 association is correct
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

private createChildDevices() {
	logging "debug", "createChildDevices"	
	addChildDevice(
		"ClassicGOD",
		"Fibaro Double Switch 2 Child Device", 
		"${device.deviceNetworkId}-2", 
		[isComponent: false, name: "${device.displayName} (CH2)"]
	)
}

/*
###################
## Device config ##
###################
*/
@Field static Map  = [0x5E: 1, 0x86: 1, 0x72: 1, 0x59: 1, 0x73: 1, 0x22: 1, 0x56: 1, 0x32: 3, 0x71: 1, 0x98: 1, 0x7A: 1, 0x25: 1, 0x5A: 1, 0x85: 2, 0x70: 2, 0x8E: 2, 0x60: 3, 0x75: 1, 0x5B: 1]

@Field static parameterMap = [
	[key: "restoreState", num: 9, size: 1, type: "enum", options: [
			0: "power off after power failure", 
			1: "restore state"
		], def: "1", title: "Restore state after power failure"],
	[key: "ch1operatingMode", num: 10, size: 1, type: "enum", options: [
			0: "standard operation", 
			1: "delay ON", 
			2: "delay OFF", 
			3: "auto ON", 
			4: "auto OFF", 
			5: "flashing mode"
		], def: "0", title: "First channel - Operating mode"],
	[key: "ch1reactionToSwitch", num: 11, size: 1, type: "enum", options: [
			0: "cancel and set target state", 
			1: "no reaction", 
			2: "reset timer"
		], def: "0", title: "Reaction to switch for delay/auto ON/OFF modes"],
	[key: "ch1timeParameter", num: 12, size: 2, type: "number", def: 50, min: 0, max: 32000, title: "First channel - Time parameter for delay/auto ON/OFF modes"],
	[key: "ch1pulseTime", num: 13, size: 2, type: "enum", options: [
			1: "0.1 s",
			5: "0.5 s",
			10: "1 s",
			20: "2 s",
			30: "3 s",
			40: "4 s",
			50: "5 s",
			60: "6 s",
			70: "7 s",
			80: "8 s",
			90: "9 s",
			100: "10 s",
			300: "30 s",
			600: "60 s",
			6000: "600 s"
		], def: 5, min: 1, max: 32000, title: "First channel - Pulse time for flashing mode"],
	[key: "ch2operatingMode", num: 15, size: 1, type: "enum", options: [
			0: "standard operation", 
			1: "delay ON", 
			2: "delay OFF", 
			3: "auto ON", 
			4: "auto OFF", 
			5: "flashing mode"
		], def: "0", title: "Second channel - Operating mode"],
	[key: "ch2reactionToSwitch", num: 16, size: 1, type: "enum", options: [ 
			0: "cancel and set target state", 
			1: "no reaction", 
			2: "reset timer"
		], def: "0", title: "Second channel - Restore state after power failure"],
	[key: "ch2timeParameter", num: 17, size: 2, type: "number", def: 50, min: 0, max: 32000, title: "Second channel - Time parameter for delay/auto ON/OFF modes"],
	[key: "ch2pulseTime", num: 18, size: 2, type: "enum", options: [
			1: "0.1 s",
			5: "0.5 s",
			10: "1 s",
			20: "2 s",
			30: "3 s",
			40: "4 s",
			50: "5 s",
			60: "6 s",
			70: "7 s",
			80: "8 s",
			90: "9 s",
			100: "10 s",
			300: "30 s",
			600: "60 s",
			6000: "600 s"
		], def: 5, min: 1, max: 32000, title: "Second channel - Pulse time for flashing mode"],
	[key: "switchType", num: 20, size: 1, type: "enum", options: [
			0: "momentary switch", 
			1: "toggle switch (contact closed - ON, contact opened - OFF)", 
			2: "toggle switch (device changes status when switch changes status)"
		], def: "2", title: "Switch type"],
	[key: "flashingReports", num: 21, size: 1, type: "enum", options: [
			0: "do not send reports", 
			1: "sends reports"
		], def: "0", title: "Flashing mode - reports"],
	[key: "s1scenesSent", num: 28, size: 1, type: "enum", options: [
			0: "do not send scenes", 
			1: "key pressed 1 time", 
			2: "key pressed 2 times", 
			3: "key pressed 1 & 2 times", 
			4: "key pressed 3 times", 
			5: "key pressed 1 & 3 times", 
			6: "key pressed 2 & 3 times", 
			7: "key pressed 1, 2 & 3 times", 
			8: "key held & released", 
			9: "key Pressed 1 time & held", 
			10: "key pressed 2 times & held", 
			11: "key pressed 1, 2 times & held", 
			12: "key pressed 3 times & held", 
			13: "key pressed 1, 3 times & held", 
			14: "key pressed 2, 3 times & held", 
			15: "key pressed 1, 2, 3 times & held"
		], def: "0", title: "Switch 1 - scenes sent"],
	[key: "s2scenesSent", num: 29, size: 1, type: "enum", options: [
			0: "do not send scenes", 
			1: "key pressed 1 time", 
			2: "key pressed 2 times", 
			3: "key pressed 1 & 2 times", 
			4: "key pressed 3 times", 
			5: "key pressed 1 & 3 times", 
			6: "key pressed 2 & 3 times", 
			7: "key pressed 1, 2 & 3 times", 
			8: "key held & released", 
			9: "key Pressed 1 time & held", 
			10: "key pressed 2 times & held", 
			11: "key pressed 1, 2 times & held", 
			12: "key pressed 3 times & held", 
			13: "key pressed 1, 3 times & held", 
			14: "key pressed 2, 3 times & held", 
			15: "key pressed 1, 2, 3 times & held"
		], def: "0", title: "Switch 2 - scenes sent"],
 	[key: "ch1powerReports", num: 50, size: 1, type: "number", def: 20, min: 0, max: 100, title: "First channel - power reports"],
	[key: "ch1powerTime", num: 51, size: 1, type: "number", def: 10, min: 0, max: 120, title: "First channel - minimal time between power reports"],
	[key: "ch1energyReports", num: 53, size: 2, type: "enum", options: [
			1: "0.01 kWh",
			10: "0.1 kWh",
			50: "0.5 kWh",
			100: "1 kWh",
			500: "5 kWh",
			1000: "10 kWh"
		], def: 100, min: 0, max: 32000, title: "First channel - energy reports"], 
	[key: "ch2powerReports", num: 54, size: 1, type: "number", def: 20, min: 0, max: 100, title: "Second channel - power reports"],
	[key: "ch2powerTime", num: 55, size: 1, type: "number", def: 10, min: 0, max: 120, title: "Second channel - minimal time between power reports"],
	[key: "ch2energyReports", num: 57, size: 2, type: "enum", options: [
			1: "0.01 kWh",
			10: "0.1 kWh",
			50: "0.5 kWh",
			100: "1 kWh",
			500: "5 kWh",
			1000: "10 kWh"
		], def: 100, min: 0, max: 32000, title: "Second channel - energy reports"], 
	[key: "periodicPowerReports", num: 58, size: 2, type: "number", def: 3600, min: 0, max: 32000, title: "Periodic power reports"], 
	[key: "periodicEnergyReports", num: 59, size: 2, type: "number", def: 3600, min: 0, max: 32000, title: "Periodic energy reports"],
	[key: "mesureDevice", num: 60, size: 1, type: "enum", options: [
			0: "function inactive", 
			1: "function active"
		], def: "0", title: "Measuring energy consumed by the device itself"]
]
