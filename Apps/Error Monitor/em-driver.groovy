/**
 *  ****************  Error Monitor Driver  ****************
 *
 *  Design Usage:
 *  This driver opens a webSocket to capture Log info.
 *
 *  Copyright 2022 Bryan Turcotte (@bptworld)
 *  
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Remember...I am not a professional programmer, everything I do takes a lot of time and research (then MORE research)!
 *  Donations are never necessary but always appreciated.  Donations to support development efforts are accepted via: 
 *
 *  Paypal at: https://paypal.me/bptworld
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  If modifying this project, please keep the above header intact and add your comments/credits below - Thank you! -  @BPTWorld
 *
 *  App and Driver updates can be found at https://github.com/bptworld/Hubitat
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Changes:
 *
 *  1.0.9 - 04/16/22 - For the love of commas!
 *  1.0.8 - 04/15/22 - Minor mods
 *  1.0.7 - 04/15/22 - Took another crack at makeList, added 'useSafety' feature
 *  1.0.6 - 04/06/22 - rinse and repeat
 *  1.0.5 - 04/05/22 - Fixed an issue with makeList
 *  1.0.4 - 04/04/22 - Added option for repeating messages
 *  1.0.3 - 04/03/22 - More adjustments to makeList
 *  1.0.2 - 04/03/22 - Issue with makeList
 *  1.0.1 - 03/31/22 - Adjustments
 *  1.0.0 - 03/25/22 - Initial release
 *
 */

import groovy.json.*

metadata {
	definition (name: "Error Monitor Driver", namespace: "BPTWorld", author: "Bryan Turcotte", importUrl: "") {
   		capability "Actuator"
        capability "Initialize"
        capability "Switch"
        
        command "closeConnection"
        command "clearAllData"
        command "appStatus"
        command "clearLogData"
        command "resetCount"
        
        attribute "switch", "string"
        attribute "status", "string"
        attribute "bpt-lastLogMessage", "string"       
        attribute "bpt-logData", "string"        
        attribute "numOfCharacters", "number"
        attribute "keywords", "string"
        attribute "nKeywords", "string"
        attribute "level", "string"
        attribute "appStatus", "string"
        attribute "useSwitch", "string"
    }
    preferences() {    	
        section(){
            input name: "about", type: "paragraph", element: "paragraph", title: "<b>Error Monitor Driver</b>", description: ""
            input("disableConnection", "bool", title: "Disable Connection", required: true, defaultValue: false)
            input("fontSize", "text", title: "Font Size", required: true, defaultValue: "15")
			input("hourType", "bool", title: "Time Selection (Off for 24h, On for 12h)", required: false, defaultValue: false)
            input("logEnable", "bool", title: "Enable logging", required: true, defaultValue: false)
        }
    }
}

def setVersion() {
    state.version = "1.0.9"
}

def installed(){
    log.info "Error Monitor Driver has been Installed"
    clearAllData()
    initialize()
}

def updated() {
    log.info "Error Monitor Driver has been Updated"
    initialize()
}

def initialize() {
    if(logEnable) log.info "In initialize"
    setVersion()
    if(disableConnection) {
        if(logEnable) log.info "Error Monitor Driver (${state.version}) - webSocket Connection is Disabled in the Device"
    } else {
        if(logEnable) log.info "Error Monitor Driver (${state.version}) - Connecting webSocket"
        atomicState.isWorking = false
        interfaces.webSocket.connect("ws://localhost:8080/logsocket")
    }
}

void uninstalled() {
	interfaces.webSocket.close()
}

def closeConnection() {
    interfaces.webSocket.close()
    if(logEnable) log.warn "Error Monitor Driver - Closing webSocket"
}

def resetCount() {
    state.sameCount = 1
    if(logEnable) log.debug "Resetting the Same Count to 1"
}

def webSocketStatus(String socketStatus) {
    if(logEnable) log.debug "In webSocketStatus - socketStatus: ${socketStatus}"
	if(socketStatus.startsWith("status: open")) {
		if(logEnable) log.warn "Error Monitor Driver - Connected"
        sendEvent(name: "status", value: "connected", displayed: true)
        pauseExecution(500)
        state.delay = null
        return
	} else if(socketStatus.startsWith("status: closing")) {
		if(logEnable) log.warn "Error Monitor Driver - Closing connection"
        sendEvent(name: "status", value: "disconnected")
        return
	} else if(socketStatus.startsWith("failure:")) {
		if(logEnable) log.warn "Error Monitor Driver - Connection has failed with error [${socketStatus}]."
        sendEvent(name: "status", value: "disconnected", displayed: true)
        autoReconnectWebSocket()
	} else {
        if(logEnable) log.warn "WebSocket error, reconnecting."
        autoReconnectWebSocket()
	}
}

def autoReconnectWebSocket() {
    setVersion()
    state.delay = (state.delay ?: 0) + 30    
    if(state.delay > 600) state.delay = 600
    if(logEnable) log.warn "Error Monitor Driver (${state.version}) - Connection lost, will try to reconnect in ${state.delay} seconds"
    runIn(state.delay, initialize)
}

def parse(String description) {
    def aStatus = device.currentValue('appStatus')
    if(aStatus == "active") {
        theData = "${description}"
        // This is what the incoming data looks like
        //{"name":"Error Monitor","msg":"Error Monitor Driver - Connected","id":365,"time":"2019-11-24 10:05:07.518","type":"dev","level":"warn"}
        // name, msg, id, time, type, level 
        if(state.sameCount == null) state.sameCount = 1
        def message =  new JsonSlurper().parseText(theData)                      
        theLevel = message.level.toLowerCase()
        if(theLevel == "error") {
            theName = message.name
            theMsg = message.msg.toLowerCase().replace(",","")
            if(state.lastMsg == null) state.lastMsg = "-"
            if(theMsg == state.lastMsg) {
                if(parent.sendDup) {
                    device.on()
                    makeList(theName, theMsg)
                } else {
                    if(logEnable) log.info "New message is the same as last message, so skipping!"
                }
                if(state.sameCount >= 11 && parent.useSafety) {
                    log.warn "************************************************************"
                    log.warn "The same Error has occurred 10 times in a row."
                    log.warn "Error Monitor is CLOSING its connection!"
                    log.warn "Please fix the Error before continuing."
                    log.warn "************************************************************"
                    closeConnection()
                    app?.updateSetting("closeConnection",[value:"true",type:"bool"])
                    state.sameCount = 1
                } else {
                    state.sameCount += 1
                }
            } else {
                state.sameCount = 1
                device.on()
                state.lastMsg = theMsg
                makeList(theName, theMsg)
            }
        }
    }
}

def on() {
    sendEvent(name: "switch", value: "on", displayed: true)
}

def off() {
    sendEvent(name: "switch", value: "off", displayed: true)
}

def makeList(theName,theMsg) {
    if(atomicState.isWorking == null) atomicState.isWorking = false
    if(!atomicState.isWorking) {
        atomicState.isWorking = true
        if(logEnable) log.debug "In makeList - working on - theName: ${theName} - ${theMsg}"
        try {
            if(state.list == null) state.list = []
            getDateTime()
            last = "${theName}::${newDate}::${theMsg}"
            state.list.add(0,last)
            if(logEnable) log.debug "In makeList - added to list - last: ${last}"

            listSize = state.list.size() ?: 0
            if(logEnable) log.debug "In makeList - listSize: ${listSize}"        
            if (listSize > 10) {
                if(logEnable) log.debug "In makeList - List size is greater than 10 - Removing oldest entry" 
                state.list.removeAt(listSize-1)
                listSize = state.list.size()
            }
            lines = state.list.toString().split(",")

            theData = "<div style='overflow:auto;height:90%'><table style='text-align:left;font-size:${fontSize}px'><tr><td width=20%><td width=1%><td width=10%><td width=1%><td width=68%>"

            for (i=0;i<listSize;i++) {
                combined = theData.length() + lines[i].length() + 16
                if(combined < 1000) {
                    //if(logEnable) log.debug "In makeList - lines$i: $lines[i]"
                    def sData = lines[i].split("::") 
                    try{ 
                        tName = sData[0].replace("[","")
                    } catch(e) { tName = "NoData" }
                    try{ 
                        tDate = sData[1]
                    } catch(e) { tDate = "NoData" }
                    try{ 
                        tMsg = sData[2].replace("]","")
                    } catch(e) { tMsg = "NoData" }
                    theData += "<tr><td>${tName} <td> - <td>${tDate}<td> - <td>${tMsg}"
                }
            }

            theData += "</table></div>"
            dataCharCount1 = theData.length()
            if(dataCharCount1 <= 1024) {
                if(logEnable) log.debug "Error Monitor Attribute - theData - ${dataCharCount1} Characters"
            } else {
                theData = "Error Monitor - Too many characters to display on Dashboard (${dataCharCount1}) - removing oldest entry"
                state.list.removeAt(listSize)
            }

            sendEvent(name: "bpt-logData", value: theData, displayed: true)
            sendEvent(name: "numOfCharacters", value: dataCharCount1, displayed: true)
            sendEvent(name: "bpt-lastLogMessage", value: theMsg, displayed: true)
            atomicState.isWorking = false
        }
        catch(e) {
            setVersion()
            log.warn "In makeList (${state.version}) - listSize: ${listSize} - lines$i: $lines[i]"
            log.warn "Error Monitor Driver (${state.version}) - In makeList - There was an error while making the list!"
            log.warn(getExceptionMessageWithLine(e))
            atomicState.isWorking = false
        }
    }
}

def appStatus(data){
	if(logEnable) log.debug "Error Monitor Driver - In appStatus"
    sendEvent(name: "appStatus", value: data, displayed: true)
}

def clearAllData(){
    setVersion()
	if(logEnable) log.debug "Error Monitor Driver (${state.version}) - Clearing ALL data"
    off()
    theMsg = "-"
    logCharCount = "0"   
    state.clear()
    state.list = []
    sendEvent(name: "bpt-logData", value: state.list, displayed: true)	
    sendEvent(name: "bpt-lastLogMessage", value: theMsg, displayed: true)
    sendEvent(name: "numOfCharacters", value: logCharCount, displayed: true)
    
}

def clearLogData(){
    setVersion()
	if(logEnable) log.debug "Error Monitor Driver (${state.version}) - Clearing the Log Data"
    state.list = []
    sendEvent(name: "bpt-logData", value: state.list, displayed: true)
    sendEvent(name: "numOfCharacters", value: "", displayed: true)
}

def getDateTime() {
	def date = new Date()
	if(hourType == false) newDate=date.format("MM/d-HH:mm")
	if(hourType == true) newDate=date.format("MM/d-hh:mm")
    return newDate
}
