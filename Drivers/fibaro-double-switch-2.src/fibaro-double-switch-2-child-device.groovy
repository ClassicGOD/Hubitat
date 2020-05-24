/**
 *  Fibaro Double Switch 2 Child Device
 *
 *  Copyright 2020 Artur Draga
 *
 */
metadata {
	definition (name: "Fibaro Double Switch 2 Child Device", namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Switch"
		capability "Energy Meter"
		capability "Power Meter"

		command "toggle"
	}
}

def on() { parent.childOn() }

def off() {	parent.childOff() }

def toggle() { parent.childToggle() }
