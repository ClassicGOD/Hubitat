/*
 *	Fibaro RGBW Controller
 *
 *	Copyright 2020 Artur Draga
 *	
 *
 */
import groovy.transform.Field 

metadata {
	definition (name: "Fibaro RGBW Controller",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Power Meter"
		capability "Refresh"
		capability "Light"
		capability "Color Control"
		capability "Switch Level"
		
		command "toggle"
		command "setRedLevel", [[name:"Level*", type: "NUMBER", description: "0 to 100"]]
		command "setGreenLevel", [[name:"Level*", type: "NUMBER", description: "0 to 100"]]
		command "setBlueLevel", [[name:"Level*", type: "NUMBER", description: "0 to 100"]]
		command "setWhiteLevel", [[name:"Level*", type: "NUMBER", description: "0 to 100"]]
		command "programStorm"
		command "programDeepFade"
		command "programLiteFade"
		command "programPolice"
		command "programStop"

		attribute "syncStatus", "string"
		attribute "redLevel", "number"
		attribute "greenLevel", "number"
		attribute "blueLevel", "number"
		attribute "whiteLevel", "number"
		
		fingerprint  mfr:"010F", prod:"0900", deviceId:"1000", inClusters:"0x27,0x72,0x86,0x26,0x60,0x70,0x32,0x31,0x85,0x33" 
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
		
		input name: "ep2Child", type: "bool", title: "Create child device for channel 1 (red, endpoint 2)", defaultValue: false
		input name: "ep3Child", type: "bool", title: "Create child device for channel 2 (green, endpoint 3)", defaultValue: false
		input name: "ep4Child", type: "bool", title: "Create child device for channel 3 (blue, endpoint 4)", defaultValue: false
		input name: "ep5Child", type: "bool", title: "Create child device for channel 4 (white, endpoint 5)", defaultValue: false
		
		input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: true
		input name: "infoEnable", type: "bool", title: "Enable info logging", defaultValue: true
		
	}
}

def on(Integer ep = null) { encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: 255),ep) }

def off(Integer ep = null) { encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: 0),ep) }

def toggle(Integer ep = null) { device.currentValue((ep == null) ? "level" : epList[ep]) == 0 ? on(ep):off(ep) }

def setLevel(BigDecimal level, duration = null, Integer ep = null) { encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (level > 0) ? level.shortValueExact()-1 : 0), ep) }

def setRedLevel(level) { setLevel(level, null, 2) }

def setGreenLevel(level) { setLevel(level, null, 3) }

def setBlueLevel(level) { setLevel(level, null, 4) }

def setWhiteLevel(level) { setLevel(level, null, 5) }

def setColor(value) {
	def RGB = []
	if ( value.hex ) {
		RGB = [R: Integer.parseInt(value.hex.substring(1,3),16), G: Integer.parseInt(value.hex.substring(3,5),16), B: Integer.parseInt(value.hex.substring(5,7),16)]
	} else if ( value.red || value.green || value.blue ) {
		RGB = [R: (value.red)?: 0, G: (value.green)?: 0, B: (value.blue)?: 0]
	} else {
		RGB = HSVtoRGB((value.hue?: device.currentValue("hue")?: 100) , (value.saturation?: device.currentValue("saturation")?: 100) , (value.level?: device.currentValue("level")?: 100))
	}
	encapCmd(zwave.switchColorV3.switchColorSet(red: RGB.R, green: RGB.G, blue: RGB.B, warmWhite: 0))
}

def setSaturation(value) { setColor([saturation: value]) } 

def setHue(value) { setColor([hue: value]) } 

def fireplace() { encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: 6, parameterNumber: 72, size: 1)) }

def programStorm() { encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: 7, parameterNumber: 72, size: 1)) }

def programDeepFade() { encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: 8, parameterNumber: 72, size: 1)) }

def programLiteFade() { encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: 9, parameterNumber: 72, size: 1)) }

def programPolice() { encapCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: 10, parameterNumber: 72, size: 1)) }

def programStop() {
	def cmds = []
	cmds << encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (device.currentValue("redLevel") > 0) ? device.currentValue("redLevel")-1 : 0), 2)
	cmds << encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (device.currentValue("greenLevel") > 0) ? device.currentValue("greenLevel")-1 : 0), 3)
	cmds << encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (device.currentValue("blueLevel") > 0) ? device.currentValue("blueLevel")-1 : 0), 4)
	cmds << encapCmd(zwave.switchMultilevelV3.switchMultilevelSet(value: (device.currentValue("whiteLevel") > 0) ? device.currentValue("whiteLevel")-1 : 0), 5)
	delayBetween(cmds,500)
}

def refresh() {	encapCmd(zwave.sensorMultilevelV5.sensorMultilevelGet()) }

/*
###################
## Encapsulation ##
###################
*/
def encapCmd(hubitat.zwave.Command cmd, Integer ep=null) { //for all your encap needs :D
	logging "encapCmd: ${cmd} ep: ${ep}"
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

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, ep = null) {
	logging "BasicReport value: ${cmd.value} ep: ${ep} ignored"
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd, ep=null) {
	logging "SwitchMultilevelReport value: ${cmd.value} ep: ${ep}"
	sendEvent([name: epList[ep], value: (cmd.value > 0) ? cmd.value+1 : 0, unit: "%"])
	sendEvent([name: "switch", value: (cmd.value == 0 ) ? "off": "on"])
	getChildDevice("${device.deviceNetworkId}-${ep}")?.sendEvent([name: "switch", value: (cmd.value == 0 ) ? "off": "on"])
	getChildDevice("${device.deviceNetworkId}-${ep}")?.sendEvent([name: "level", value: (cmd.value > 0) ? cmd.value+1 : 0, unit: "%"])
	runIn(2,"setHSV")
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	logging "SensorMultilevelReport value: ${cmd.scaledSensorValue} sensorType: ${cmd.sensorType}"
	if (cmd.sensorType == 4) sendEvent([name: "power", value: cmd.scaledSensorValue, unit: "W"]) 
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	logging "AssociationReport: ${cmd}"
	def cmds = []
	if (cmd.groupingIdentifier == 5) {
		if (cmd.nodeId != [zwaveHubNodeId]) {
			logging "${device.displayName} - incorrect Association for Group 5! nodeId: ${cmd.nodeId} will be changed to ${zwaveHubNodeId}", "info"
			cmds << encapCmd(zwave.associationV2.associationRemove(groupingIdentifier: 5))
			cmds << encapCmd(zwave.associationV2.associationSet(groupingIdentifier: 5, nodeId: zwaveHubNodeId))
		} else {
			logging "${device.displayName} - Association for Group 5 correct."
		}
	}
	if (cmds) { sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE)) }
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=null){ logging "unhandled zwaveEvent: ${cmd} ep: ${ep}", "warn" }

/*
####################
## Parameter Sync ##
####################
*/
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
		if (device.currentValue("syncStatus").contains("In progres")) { sendEvent(name: "syncStatus", value: "Wrong value on param ${paramData.num}") }
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
			sendEvent(name: "syncStatus", value: "Rejected Request for parameter: ${param.num}")
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
			sendEvent(name: "syncStatus", value: "In progress (parameter: ${param.num})")
			break
		} 
	}
	if (cmds) { 
		sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE))
	} else {
		logging "Sync End", "info"
		if (device.currentValue("syncStatus").contains("In progress")) { sendEvent(name: "syncStatus", value: "Complete") }
	}
}

/*
###############
## Configure ##
###############
*/

def updated() {
	logging "updated"
	if (!device.currentValue("syncStatus")) { state.clear() }
	syncNext()
	createChildDevices()
	response(encapCmd(zwave.associationV2.associationGet(groupingIdentifier: 5))) //check if hub is associated with correct group
}

/*
###########
## Other ##
###########
*/
void logging(String text, String type = "debug") { if ( this["${type}Enable"] || this["${type}Enable"] == null ) log."${type}" text } 

private createChildDevices() {
	logging "createChildDevices"	
	(2..5).each{
		if (this["ep${it}Child"]) {
			if (!getChildDevice("${device.deviceNetworkId}-${it}")) {
				addChildDevice(
					"ClassicGOD",
					"Fibaro RGBW Controller Child Device", 
					"${device.deviceNetworkId}-${it}", 
					[isComponent: false, name: "${device.displayName} (${epList[it]})"]
					)
			}
		} else {
			deleteChildDevice("${device.deviceNetworkId}-${it}")
		}
	}
}

def HSVtoRGB(Float H, Float S, Float V) { 
	logging "HSVtoRGB H:${H} S:${S} V:${V} "
	H = H*3.6 //0-100 to 0-360
	S = S/100
	V = V/100
	
	Float C = V * S
	Float X = C * ( 1 - Math.abs((H/60) % 2 - 1) )
	Float M = V - C
	
	Float tR = 0
	Float tG = 0
	Float tB = 0
	
	switch (H.trunc() as Integer) {
		case  60..119: tR = X; tG = C; break;
		case 120..179: tG = C; tB = X; break;
		case 180..239: tG = X; tB = C; break;
		case 240..299: tR = X; tB = C; break;
		case 300..359: tR = C; tB = X; break;
		default: tR = C; tG = X; 
	} 
	return [R:((tR+M)*255).round() as Integer ,G:((tG+M)*255).round() as Integer ,B:((tB+M)*255).round() as Integer ]
}

def RGBtoHSV(Float R, Float G, Float B) { 
	logging "RGBtoHSV R:${R} G:${G} B:${B} "
	R = R / 99 //0-99
	G = G / 99 //0-99
	B = B / 99 //0-99
	
	Float min = [R, G, B].min()
	Float max = [R, G, B].max()
	Float dif = max - min
	
	Float H = 0
	Float S = 0
	Float L = max
	
	if (dif > 0) {
		if (R == max) H = ((G-B) / dif % 6)
		else if (G == max) H = ((B-R) / dif + 2)
		else H = ((R-G) / dif + 4)
		S = dif / max
		H = H * 60
		if (H < 0) H += 360
	}
	return [H: (H/3.6/*0-100*/).round() as Integer, S: (S*100).round() as Integer, L: (L*100).round() as Integer]
}

def setHSV() {
	HSV = RGBtoHSV(
		(device.currentValue("redLevel") > 0)? device.currentValue("redLevel")-1 : 0,
		(device.currentValue("greenLevel") > 0)? device.currentValue("greenLevel")-1: 0,
		(device.currentValue("blueLevel") > 0)? device.currentValue("blueLevel")-1: 0
	)
	sendEvent([name: "hue", value: HSV.H])
	sendEvent([name: "saturation", value: HSV.S])
}

/*
###################
## Device config ##
###################
*/
@Field static Map commandClassVersions = [0x27: 1, 0x72: 1, 0x86: 1, 0x26: 3, 0x60: 3, 0x70: 2, 0x32: 3, 0x31: 5, 0x85: 2, 0x33: 1]

@Field static Map epList = [1: "level", 2: "redLevel", 3: "greenLevel", 4: "blueLevel", 5: "whiteLevel"]

@Field static parameterMap = [
	[key: "allControll", title: "ALL ON / ALL OFF", type: "number", num: 1, size: 1, def: 255, min: 0, max: 255 ],
	[key: "stepSize", title: "Step value", type: "number", num: 9, size: 1, def: 1, min: 1, max: 255 ],
	[key: "stepTime", title: "Time between steps", type: "number", num: 10, size: 2, def: 10, min: 0, max: 60000 ],
	[key: "deviceStatus", title: "Memorize device status at power cut", type: "enum", options: [0: "power off", 1: "last status"], num: 16, size: 1, def: 1, min: 0, max: 1 ],
	[key: "energyReporting", title: "Reporting changes in energy consumed by controlled devices", type: "number", num: 45, size: 1, def: 10, min: 0, max: 254 ]
]
