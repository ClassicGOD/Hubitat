/**
 *  Fibaro RGBW Controller Child Device
 *
 *  Copyright 2020 Artur Draga
 *
 */
metadata {
	definition (name: "Fibaro RGBW Controller Child Device", namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Switch Level"
		capability "Light"

		command "toggle"
	}
}

def on() { parent.endpointOn(device.deviceNetworkId[-1] as Integer) }

def off() {	parent.endpointOff(device.deviceNetworkId[-1] as Integer) }

def toggle() { parent.endpointToggle(device.deviceNetworkId[-1] as Integer) }

def setLevel(level, duration = null) { parent.endpointLevel(level, device.deviceNetworkId[-1] as Integer) }
