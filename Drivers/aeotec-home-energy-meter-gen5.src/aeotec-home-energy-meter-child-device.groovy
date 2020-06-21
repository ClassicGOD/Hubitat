/**
 *  Aeotec HEM Child Device
 *
 *  Copyright 2020 Artur Draga
 *
 */
metadata {
	definition (name: "Aeotec Home Energy Meter Child Device", namespace: "ClassicGOD", author: "Artur Draga") {
		capability "Energy Meter"
		capability "Power Meter"
		capability "Voltage Measurement"
		capability "Refresh"
		
		command "resetEnergy"
		
		attribute "current", "number"
	}
}

def refresh() { parent.refresh(device.deviceNetworkId[-1].toInteger()) }
def resetEnergy() { parent.resetEnergy(device.deviceNetworkId[-1].toInteger()) }
