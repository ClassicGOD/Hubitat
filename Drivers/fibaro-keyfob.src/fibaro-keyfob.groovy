/*
 *  Fibaro KeyFob
 *
 *  Copyright 2020 Artur Draga
 *	
 *	Special thanks to Eric "erocm123" Maycock for help with the code.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.transform.Field 

/*
###################
## Device config ##
###################
*/
@Field static Map commandClassVersions = [0x5E: 2, 0x59: 1, 0x80: 1, 0x56: 1, 0x7A: 3, 0x73: 1, 0x98: 1, 0x22: 1, 0x85: 2, 0x5B: 1, 0x70: 2, 0x8E: 2, 0x86: 2, 0x84: 2, 0x75: 2, 0x72: 2]

@Field static parameterMap = [ 
	[key: "unlockSeq", num: 1, size: 2, descr: "Unlocking Sequence", type: "sequence"], 
	[key: "lockBtnTim", num: 2, size: 2, descr: "Lock Time and Button", type: "buttonTime"], 
	[key: "seq1", num: 3, size: 2, descr: "First Sequence", type: "sequence"], 
	[key: "seq2", num: 4, size: 2, descr: "Second Sequence", type: "sequence"], 
	[key: "seq3", num: 5, size: 2, descr: "Third Sequence", type: "sequence"], 
	[key: "seq4", num: 6, size: 2, descr: "Fourth Sequence", type: "sequence"], 
	[key: "seq5", num: 7, size: 2, descr: "Fifth Sequence", type: "sequence"], 
	[key: "seq6", num: 8, size: 2, descr: "Sixth Sequence", type: "sequence"], 
	[key: "seqTim", num: 9, size: 1, descr: "Sequence Timeout", type: "number"], 
	[key: "btn1mode", num: 21, size: 1, descr: "Square (1) Button Mode", type: "mode"], 
	[key: "btn2mode", num: 22, size: 1, descr: "Circle (2) Button Mode", type: "mode"], 
	[key: "btn3mode", num: 23, size: 1, descr: "Saltire (3) Button Mode", type: "mode"], 
	[key: "btn4mode", num: 24, size: 1, descr: "Triangle (4) Button Mode", type: "mode"], 
	[key: "btn5mode", num: 25, size: 1, descr: "Minus (5) Button Mode", type: "mode"], 
	[key: "btn6mode", num: 26, size: 1, descr: "Plus (6) Button Mode", type: "mode"] 
] 

metadata {
	definition (name: "Fibaro KeyFob",namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Actuator"
		capability "Battery"
		capability "PushableButton"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "ReleasableButton"

		attribute "batteryStatus", "string"
		attribute "syncStatus", "string"
   
		fingerprint deviceId: "4096" , inClusters: "0x5E,0x85,0x59,0x80,0x5B,0x70,0x56,0x5A,0x7A,0x72,0x8E,0x73,0x98,0x86,0x84,0x75,0x22", mfr: "0271", prod: "4097", deviceJoinName: "Fibaro KeyFob"
		fingerprint deviceId: "4096" , inClusters: "0x5E,0x85,0x59,0x80,0x5B,0x70,0x56,0x5A,0x7A,0x72,0x8E,0x73,0x86,0x84,0x75,0x22", mfr: "0271", prod: "4097", deviceJoinName: "Fibaro KeyFob"
	}

	preferences {
		input name: "protection", title: "Protection State", type: "enum", options: [0: "Unprotected", 1: "Protection by sequence"], required: false
		input name: "unlockSeq", type: "number", title: "Unlocking Sequence:", required: false
		input name: "lockTim", type: "number", title: "Time To Lock", required: false
		input name: "lockBtn", type: "number", title: "Locking Button", required: false

		parameterMap.findAll( {it.key.contains('seq')} ).each {
			input name: it.key, title: it.descr, type: "number", required: false			
		}
		
		parameterMap.findAll( {it.key.contains('btn')} ).each {
			input (
				name: it.key,
				title: it.descr,
				type: "enum",
				options: [
					1: "1 Click", 
					2: "2 Clicks", 
					3: "1 & 2 Clicks", 
					4: "3 Clicks", 
					5: "1 & 3 Clicks", 
					6: "2 & 3 Clicks", 
					7: "1, 2 & 3 Clicks", 
					8: "Hold and Release", 
					9: "1 Click & Hold (Default)", 
					10: "2 Clicks & Hold",
					11: "1, 2 Clicks & Hold",
					12: "3 Clicks & Hold",
					13: "1, 3 Clicks & Hold",
					14: "2, 3 Clicks & Hold",
					15: "1, 2, 3 Clicks & Hold"
				],
				required: false
			)
		}
		
		input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: true
		input name: "infoEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
	if (cmd) {
		zwaveEvent(cmd)
	}
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd) {
	logging "debug", "WakeUpNotification: ${cmd}"
	logging "info", "woke up"
	def cmdsSet = []
	def cmdsGet = []
	def cmds = []
	def Integer cmdCount = 0

	cmdsGet += secureCmd(zwave.batteryV1.batteryGet())
	if (device.currentValue("syncStatus") != "synced") {
		parameterMap.each {
			if (state."${it.key}" == null) {state."${it.key}" = [value: null, state: "synced"]}
			if (state."$it.key".value != null && state."${it.key}".state == "notSynced") {
				cmdsSet += secureCmd(zwave.configurationV2.configurationSet(scaledConfigurationValue: state."${it.key}".value, parameterNumber: it.num , size: it.size))
				cmdsGet += secureCmd(zwave.configurationV2.configurationGet(parameterNumber: it.num))
				cmdCount = cmdCount + 1
			}
		}
		if (state.protection == null) {state."${it.key}" = [value: null, state: "synced"]}
		if ( state.protection.value != null && state.protection.state == "notSynced") {
			cmdsSet += secureCmd(zwave.protectionV1.protectionSet(protectionState: state.protection.value ))
			cmdsGet += secureCmd(zwave.protectionV1.protectionGet())
			cmdCount = cmdCount + 1
		}
		sendEvent(name: "syncStatus", value: "inProgress")
		runIn((5+cmdCount*1.5).toInteger(), "syncCheck")
	}
	
	cmds = cmdsSet + cmdsGet
	sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds,500), hubitat.device.Protocol.ZWAVE))
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
	logging "debug", "ConfigurationReport: ${cmd}"
	def paramData
	paramData = parameterMap.find( {it.num == cmd.parameterNumber } )
	if (state."${paramData.key}".value == cmd.scaledConfigurationValue) {
		state."${paramData.key}".state = "synced"
	}
	logging "info", "parameter: ${cmd.parameterNumber} name: ${paramData.key} type: ${paramData.type} value: ${cmd.scaledConfigurationValue} state: ${state."${paramData.key}".state}"
}

def zwaveEvent(hubitat.zwave.commands.protectionv2.ProtectionReport cmd) {
	logging "debug", "ProtectionReport: ${cmd}"
	logging "info", "Protection set to: ${cmd.localProtectionState}"
	if (state.protection.value == cmd.localProtectionState) {
		state.protection.state = "synced"
	}
}

def zwaveEvent(hubitat.zwave.commands.applicationstatusv1.ApplicationRejectedRequest cmd) {
	logging "warn", "rejected onfiguration!"
	sendEvent(name: "syncStatus", value:"failed")
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
	logging "debug", "BatteryReport: ${cmd}"
	logging "info", "battery is $cmd.batteryLevel%"
	sendEvent(name: "battery", value: cmd.batteryLevel)
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapCmd = cmd.encapsulatedCommand()
	def result = []
	if (encapCmd) {
		logging "debug", "SecurityMessageEncapsulation: ${cmd}"
		result += zwaveEvent(encapCmd)
	} else {
	   logging "warn", "Unable to extract secure cmd from: ${cmd}"
	}
	return result
}

def zwaveEvent(hubitat.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
	logging "debug", "CentralSceneNotification: ${cmd}"
	def realButton = cmd.sceneNumber as Integer //1-6 physical, 7-12 sequences
	def keyAttribute = cmd.keyAttributes as Integer
	def Integer mappedButton
	def String action
	def String description
	/* buttons:
		1-6		Single Presses, Double Presses, Hold, Release
		7-12	Sequences
		12-18	Tripple Presses */
	mappedButton = realButton
	switch (keyAttribute) {
		case 0: action = "pushed"; break
		case 1: action = "released"; break
		case 2: action = "held"; break
		case 3: action = "doubleTapped"; break
		case 4: mappedButton = mappedButton + 12; action = "pushed"; break
	}
	
	description = "button ${mappedButton} was ${action}" + ( keyAttribute == 4 ? " ( button ${realButton} trippletap )" : "" ) + ( realButton>6 ? " ( sequence ${realButton-6} executed )":"" )
   
	logging "info", description
	sendEvent(name:action, value:mappedButton, descriptionText: description, isStateChange:true, type:type)
}

void zwaveEvent(hubitat.zwave.Command cmd){
	logging "warn", "unhandled zwaveEvent: ${cmd}"
}

def secureCmd(cmd) { //zwave secure encapsulation
	logging "debug", "secureCmd: ${cmd}"
	if (getDataValue("zwaveSecurePairingComplete") == "true") {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		return cmd.format()
	}	
}

/*
#############################
## Configure, Updated etc. ##
#############################
*/
void configure(){
	logging "debug", "configure"
	sendEvent(name: "numberOfButtons", value: 18)
}

void updated() {
	logging "debug", "updated"
	sendEvent(name: "numberOfButtons", value: 18)
	def Integer tempValue 
	def Integer syncRequired = 0
	parameterMap.each {
		if(this[it.key] != null || it.key == "lockBtnTim" ) {
			switch (it.type) {
				case "buttonTime": tempValue = btnTimToValue(); break
				case "sequence": tempValue = seqToValue(this[it.key]); break
				case "mode":  tempValue = this[it.key] as Integer; break
				case "number": tempValue = this[it.key]; break
				}
			if (state."${it.key}" == null) { state."${it.key}" = [value: null, state: "synced"] }
			if (state."${it.key}".value != tempValue) {
				syncRequired = 1
				state."${it.key}".value = tempValue
				state."${it.key}".state = "notSynced"
			}
		}
	}
	if (state.protection == null) { state.protection = [value: null, state: "synced"] }
	if (state.protection != null) {
		tempValue = protection as Integer
		if (state.protection.value != tempValue && protection != null) {
			syncRequired = 1
			state.protection.value = tempValue
			state.protection.state = "notSynced"
		}
	}
	if ( syncRequired !=0 ) { sendEvent(name: "syncStatus", value: "pending") }
}

/*
############
## Custom ##
############
*/
void logging(String type, String text) { //centralized logging
	text = "${device.displayName}: " + text
	if (debugEnable && type == "debug") log.debug text
	if (infoEnable && type == "info") log.info text
	if (type == "warn") log.warn text
}

def seqToValue(sequence) { //convert sequence to value
	logging "debug", "seqToValue"
	sequence = sequence as String
	def Integer size = sequence.length()
	def Integer result = 0
	if (size > 5) { size = 5; logging "info", "Sequence too long, will be trimmed." }
	(0..size-1).each{ n ->
			result = result + ((sequence[n] as Integer) * (8**n))
	}
	return result
}

def btnTimToValue () { //convert lockTim and lockBtn to value
	logging "debug", "btnTimToValue"
	def Integer buttonVal
	def Integer timeVal
	def Integer tempValue
	if (lockBtn) { buttonVal = (lockBtn as Integer)*256 } else { buttonVal = 0 }
	if (lockTim) { timeVal = lockTim } else { timeVal = 0 }
	if (timeVal > 255) { timeVal = 255 }
	if (timeVal < 5 && timeVal != 0) { timeVal = 5 }
	if (buttonVal > 1536) { buttonVal = 1536 }
	return buttonVal+timeVal
}

def syncCheck() { //check if sync is complete
	logging "debug", "syncCheck"
	def Integer count = 0
	if (device.currentValue("syncStatus") != "synced") { parameterMap.each { if (state."${it.key}".state == "notSynced" ) {count = count + 1} } }
	if (state.protection.state != "synced") { count = count + 1 }
	if (count == 0) {
		sendEvent(name: "syncStatus", value: "synced")
	} else {
		if (device.currentValue("syncStatus") != "failed") { sendEvent(name: "syncStatus", value: "incomplete") }
	}
}
