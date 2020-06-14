
/*
 *  Aeotec Home Energy Meter Gen 5
 *
 *  Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Aeotec Home Energy Meter Gen 5",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Energy Meter"
		capability "Power Meter"
		capability "Voltage Measurement"
		capability "Refresh"

		command "resetEnergyAll"
		
		attribute "syncStatus", "string"
		attribute "current", "number"
		
		fingerprint  mfr:"0086", prod:"0002", deviceId:"005F", inClusters:"0x5E,0x86,0x72,0x32,0x56,0x60,0x8E,0x70,0x59,0x85,0x7A,0x73,0x5A" 
		fingerprint  mfr:"0086", prod:"0002", deviceId:"005F", inClusters:"0x5E,0x86,0x72,0x32,0x56,0x60,0x8E,0x70,0x59,0x85,0x7A,0x73,0x5A,0x98" 
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
		input name: "infoEnable", type: "bool", title: "Enable info logging", defaultValue: true}
}

def refresh() { 
	def cmds = []
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 2))
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 4))
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 5))
	delayBetween(cmds,1000)
}

def refreshChild(dni) { 
	Integer ep = (dni-"${device.deviceNetworkId}-") as Integer
	def cmds = []
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 0),ep))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 2),ep))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 4),ep))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 5),ep))
	sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,1000), hubitat.device.Protocol.ZWAVE))
}

def resetEnergyAll() {
	def cmds = []
	cmds << secureCmd(zwave.meterV3.meterReset())
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 0),1))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 0),2))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 0),3))
	delayBetween(cmds,1000)
}

def resetEnergyChild(dni) {
	Integer ep = (dni-"${device.deviceNetworkId}-") as Integer
	def cmds = []
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterReset(),ep))
	cmds << secureCmd(endpointCmd(zwave.meterV3.meterGet(scale: 0),ep))
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 0))
	sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,1000), hubitat.device.Protocol.ZWAVE))
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

def endpointCmd(cmd, ep) { //zwave MultiChannel Encap
	logging "debug", "endpointCmd: ${cmd}"
	return zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:ep).encapsulate(cmd)
}

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

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
	def encapCmd = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data, commandClassVersions[cmd.commandClass as Integer]?: 1)
	if (encapCmd) {
		logging "debug", "Crc16Encap: ${cmd}"
		zwaveEvent(encapCmd)
	} else {
	   logging "warn", "Unable to extract Crc16Encap cmd from: ${cmd}"
	}
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd, ep=null) {
	logging "debug", "MeterReport value: ${cmd.scaledMeterValue} scale: ${cmd.scale} ep: $ep"
	
	switch (cmd.scale) {
		case 0: type = "energy"; unit = "kWh"; break; 
		case 1: type = "totalEnergy"; unit = "kVAh"; break;
		case 2: type = "power"; unit = "W"; break;	
		case 4: type = "voltage"; unit = "V"; break;
		case 5: type = "current"; unit = "A"; break;
	}
	
	if (ep == null) {
		sendEvent([name: type, value: cmd.scaledMeterValue, unit: unit])
	} else {
		getChildDevice("${device.deviceNetworkId}-${ep}")?.sendEvent([name: type, value: cmd.scaledMeterValue, unit: unit]) 
	}
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=null){
	logging "warn", "unhandled zwaveEvent: ${cmd} ep: ${ep}"
	

}

/*
########################
## Parameter Sync etc ##
########################
*/
def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelEndPointReport cmd) {
	logging "debug",  "MultiChannelEndPointReport: dynamic: ${cmd.dynamic} endPoints: ${cmd.endPoints} identical: ${cmd.identical}"
	
	if ( !childDevices && cmd.endPoints > 1 ) {
		(1..cmd.endPoints).each() {
			addChildDevice(
				"ClassicGOD",
				"Aeotec Home Energy Meter Child Device", 
				"${device.deviceNetworkId}-${it}", 
				[isComponent: false, name: "${device.displayName} (Clamp ${it})"]
			)	
		}
	}
}

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
	runIn(3,"syncNext")
	return response(secureCmd(zwave.multiChannelV4.multiChannelEndPointGet())) //check how many endpoints/clamps
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

@Field static Map commandClassVersions = [0x5E: 2, 0x86: 1, 0x72: 1, 0x32: 3, 0x56: 1, 0x60: 3, 0x8E: 2, 0x70: 2, 0x59: 1, 0x85: 2, 0x7A: 2, 0x73: 1, 0x5A: 1, 0x98: 1] //Aeotec Home Energy Meter Gen 5

@Field static parameterMap = [
	[key: "reportingThreshold", num: 3, size: 1, type: "enum", options: [0: "0 - disable", 1: "1 - enable"], def: "1", title: "Reporting Threshold"],
	[key: "thresholdHEM", num: 4, size: 2, type: "number", def: 50, min: 0, max: 60000, title: "HEM threshold"], 
	[key: "thresholdClamp1", num: 5, size: 2, type: "number", def: 50, min: 0, max: 60000, title: "Clamp 1 threshold"], 
	[key: "thresholdClamp2", num: 6, size: 2, type: "number", def: 50, min: 0, max: 60000, title: "Clamp 2 threshold"], 
	[key: "thresholdClamp3", num: 7, size: 2, type: "number", def: 50, min: 0, max: 60000, title: "Clamp 3 threshold"], 
	[key: "percentageHEM", num: 8, size: 1, type: "number", def: 10, min: 0, max: 100, title: "HEM percentage"], 
	[key: "percentageClamp1", num: 9, size: 1, type: "number", def: 10, min: 0, max: 100, title: "Clamp 1 percentage"], 
	[key: "percentageClamp2", num: 10, size: 1, type: "number", def: 10, min: 0, max: 100, title: "Clamp 2 percentage"], 
	[key: "percentageClamp3", num: 11, size: 1, type: "number", def: 10, min: 0, max: 100, title: "Clamp 3 percentage"],
	[key: "crcReporting", num: 13, size: 1, type: "enum", options: [0: "0 - disable", 1: "1 - enable"], def: "0", title: "CRC-16 reporting"],
	[key: "group1", num: 101, size: 4, type: "number", def: 2, min: 0, max: 4210702, title: "Group 1"],
	[key: "group2", num: 102, size: 4, type: "number", def: 1, min: 0, max: 4210702, title: "Group 2"],
	[key: "group3", num: 103, size: 4, type: "number", def: 0, min: 0, max: 4210702, title: "Group 3"],
	[key: "timeGroup1", num: 111, size: 4, type: "number", def: 5, min: 0, max: 268435456, title: "Group 1 time interval"],
	[key: "timeGroup2", num: 112, size: 4, type: "number", def: 120, min: 0, max: 268435456, title: "Group 2 time interval"],
	[key: "timeGroup3", num: 113, size: 4, type: "number", def: 120, min: 0, max: 268435456, title: "Group 3 time interval"]
]
