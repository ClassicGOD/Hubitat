
/*
 *	Aeotec Home Energy Meter Gen 5
 *
 *	Copyright 2020 Artur Draga
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

		command "resetEnergy"
		
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

def refresh(Integer ep = null) { 
	def cmds = []
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 0),ep)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 2),ep)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 4),ep)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 5),ep)
	delayBetween(cmds,1000)
}

def resetEnergy(Integer ep = null) {
	def cmds = []
	cmds << encapCmd(zwave.meterV3.meterReset(),ep)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 0))
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 0),1)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 0),2)
	cmds << encapCmd(zwave.meterV3.meterGet(scale: 0),3)
	delayBetween(cmds,1000)
}

/*
###################
## Encapsulation ##
###################
*/
def encapCmd(hubitat.zwave.Command cmd, Integer ep=null) { 
	logging "encapCmd: ${cmd} ep: ${ep}"
	if (ep != null) cmd = zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint: ep).encapsulate(cmd) 
	if (getDataValue("zwaveSecurePairingComplete") == "true") cmd = zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd) 
	return cmd.format()
}

/*
######################
## Parse and events ##
######################
*/
void parse(String description){
	logging "parse: ${description}"
	hubitat.zwave.Command cmd = zwave.parse(description,commandClassVersions)
	if (cmd) zwaveEvent(cmd)
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	logging "SecurityMessageEncapsulation: ${cmd}"
	def encapCmd = cmd.encapsulatedCommand()
	if (encapCmd) zwaveEvent(encapCmd)
	else logging "Unable to extract secure cmd from: ${cmd}", "warn"
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
	logging "Crc16Encap: ${cmd}"
	def encapCmd = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data, commandClassVersions[cmd.commandClass as Integer]?: 1)
	if (encapCmd) zwaveEvent(encapCmd)
	else logging "Unable to extract Crc16Encap cmd from: ${cmd}", "warn"
}

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	logging "MultiChannelCmdEncap: ${cmd}"
	def encapCmd = cmd.encapsulatedCommand()
	if (encapCmd) zwaveEvent(encapCmd, cmd.sourceEndPoint as Integer)
	else logging "Unable to extract multi channel cmd from: ${cmd}", "warn"
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd, Integer ep=null) {
	logging "MeterReport value: ${cmd.scaledMeterValue} scale: ${cmd.scale} ep: $ep"
	switch (cmd.scale) {
		case 0: type = "energy"; unit = "kWh"; break; 
		case 1: type = "totalEnergy"; unit = "kVAh"; break;
		case 2: type = "power"; unit = "W"; break;	
		case 4: type = "voltage"; unit = "V"; break;
		case 5: type = "current"; unit = "A"; break;
	}
	if (ep == null) sendEvent([name: type, value: cmd.scaledMeterValue, unit: unit])
	else getChildDevice("${device.deviceNetworkId}-${ep}")?.sendEvent([name: type, value: cmd.scaledMeterValue, unit: unit]) 
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) { 
	logging "DeviceSpecificReport: ${cmd}"
	String sN = ""
	if (cmd.deviceIdType == 1 && cmd.deviceIdDataFormat == 1) {
		(0..14).each { sN += Integer.toHexString(cmd.deviceIdData[it]).toUpperCase() }
		updateDataValue("serialNumber", sN)
	}
}

def zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {	
	logging "VersionReport: ${cmd}"
	updateDataValue("firmwareVersion", "${cmd.firmware0Version}.${cmd.firmware0SubVersion}")
	updateDataValue("protocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
}

void zwaveEvent(hubitat.zwave.Command cmd, Integer ep=null){ logging "unhandled zwaveEvent: ${cmd} ep: ${ep}", "warn" }

/*
########################
## Parameter Sync etc ##
########################
*/
def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelEndPointReport cmd) {
	logging "MultiChannelEndPointReport: dynamic: ${cmd.dynamic} endPoints: ${cmd.endPoints} identical: ${cmd.identical}"
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
	logging "ConfigurationReport: ${cmd}"
	def paramData = parameterMap.find( {it.num == cmd.parameterNumber } )
	def previousVal = state."${paramData.key}".toString()
	def expectedVal = this["${paramData.key}"].toString()
	def receivedVal = cmd.scaledConfigurationValue.toString()
	
	logging "Parameter ${paramData.key} value is ${receivedVal} expected ${expectedVal}", "info"
	if (previousVal == receivedVal && expectedVal == receivedVal) { //ignore
	} else if (expectedVal == receivedVal) {
		logging "Parameter ${paramData.key} as expected"
		state."${paramData.key}" = receivedVal
		syncNext()
	} else if (previousVal == receivedVal) {
		logging "Parameter ${paramData.key} not changed - sync failed"
		if (device.currentValue("syncStatus").contains("in progres")) { sendEvent([name: "syncStatus", value: "wrong value on param ${paramData.num}"]) }
	} else {
		logging "Parameter ${paramData.key} new value"
		device.updateSetting("${paramData.key}", [value:receivedVal, type: paramData.type])
		state."${paramData.key}" = receivedVal
	}
}

def zwaveEvent(hubitat.zwave.commands.applicationstatusv1.ApplicationRejectedRequest cmd) {
	logging "Rejected Configuration!", "warn"
	for ( param in parameterMap ) {
		if (state."$param.key".toString() != this["$param.key"].toString()) {
			sendEvent(name: "syncStatus", value: "rejected request for parameter: ${param.num}")
			break
		}
	}
}

private syncNext() {
	logging "syncNext()"
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
		logging "Sync End", "info"
		if (device.currentValue("syncStatus").contains("in progress")) { sendEvent(name: "syncStatus", value: "complete") }
	}
}

/*
#############################
## Configure, Updated etc. ##
#############################
*/
def updated() {
	logging "updated"
	cmds = []
	runIn(3,"syncNext")
	cmds << encapCmd(zwave.multiChannelV4.multiChannelEndPointGet())
	if ( getDataValue("serialNumber") == null ) cmds << encapCmd(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 0))
	cmds << encapCmd(zwave.versionV3.versionGet())
	return delayBetween(cmds,1000)
}

/*
###########
## Other ##
###########
*/
void logging(String text, String type = "debug") { if ( this["${type}Enable"] || this["${type}Enable"] == null ) log."${type}" text } 

/*
###################
## Device config ##
###################
*/
@Field static Map commandClassVersions = [0x5E: 2, 0x86: 3, 0x72: 1, 0x32: 3, 0x56: 1, 0x60: 3, 0x8E: 2, 0x70: 2, 0x59: 1, 0x85: 2, 0x7A: 2, 0x73: 1, 0x5A: 1, 0x98: 1]

@Field static parameterMap = [
	[key: "reportingThreshold", title: "Reporting Threshold", type: "enum", options: [0: "0 - disable", 1: "1 - enable"], num: 3, size: 1, def: 1, min: 0, max: 1],
	[key: "thresholdHEM", title: "HEM threshold", type: "number", num: 4, size: 2, def: 50, min: 0, max: 60000], 
	[key: "thresholdClamp1", title: "Clamp 1 threshold", type: "number", num: 5, size: 2, def: 50, min: 0, max: 60000], 
	[key: "thresholdClamp2", title: "Clamp 2 threshold", type: "number", num: 6, size: 2, def: 50, min: 0, max: 60000], 
	[key: "thresholdClamp3", title: "Clamp 3 threshold", type: "number", num: 7, size: 2, def: 50, min: 0, max: 60000], 
	[key: "percentageHEM", title: "HEM percentage", type: "number", num: 8, size: 1, def: 10, min: 0, max: 100], 
	[key: "percentageClamp1", title: "Clamp 1 percentage", type: "number", num: 9, size: 1, def: 10, min: 0, max: 100], 
	[key: "percentageClamp2", title: "Clamp 2 percentage", type: "number", num: 10, size: 1, def: 10, min: 0, max: 100], 
	[key: "percentageClamp3", title: "Clamp 3 percentage", type: "number", num: 11, size: 1, def: 10, min: 0, max: 100],
	[key: "crcReporting", title: "CRC-16 reporting", type: "enum", options: [0: "0 - disable", 1: "1 - enable"], num: 13, size: 1, def: 0, min: 0, max: 1],
	[key: "group1", title: "Group 1", type: "number", num: 101, size: 4, def: 2, min: 0, max: 4210702],
	[key: "group2", title: "Group 2", type: "number", num: 102, size: 4, def: 1, min: 0, max: 4210702],
	[key: "group3", title: "Group 3", type: "number", num: 103, size: 4, def: 0, min: 0, max: 4210702],
	[key: "timeGroup1", title: "Group 1 time interval", type: "number", num: 111, size: 4, def: 5, min: 0, max: 268435456],
	[key: "timeGroup2", title: "Group 2 time interval", type: "number", num: 112, size: 4, def: 120, min: 0, max: 268435456],
	[key: "timeGroup3", title: "Group 3 time interval", type: "number", num: 113, size: 4, def: 120, min: 0, max: 268435456]
]

