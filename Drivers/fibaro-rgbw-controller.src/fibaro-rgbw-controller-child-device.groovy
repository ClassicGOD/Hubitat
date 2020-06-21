/**
 *	Fibaro RGBW Controller Child Device
 *
 *	Copyright 2020 Artur Draga
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

def on() { parent.on(device.deviceNetworkId[-1] as Integer) }

def off() { parent.off(device.deviceNetworkId[-1] as Integer) }

def toggle() { parent.toggle(device.deviceNetworkId[-1] as Integer) }

def setLevel(level, duration = null) { parent.setLevel(level, null, device.deviceNetworkId[-1] as Integer) }
