' Golovinema TV — Roku channel over the local core bridge (core/CoreCli serve).
' Fetches /channels.json, shows a poster grid of iptv-org channels, plays HLS.
' Experimental local-only sources are resolved Android-side and are out of scope here.

sub Init()
    m.status = m.top.findNode("status")
    m.grid = m.top.findNode("grid")
    m.player = m.top.findNode("player")
    m.enteredFromKeyboard = false
    m.grid.observeField("itemSelected", "OnItemSelected")
    m.player.observeField("state", "OnPlayerState")
    ShowStatus("Loading channels…" + Chr(10) + "bridge: " + BridgeUrl())
    timer = CreateObject("roSGNode", "Timer")
    timer.duration = 1.0
    timer.observeField("fire", "LoadChannels")
    timer.control = "start"
    m.loadTimer = timer
end sub

function BridgeUrl() as string
    if m.bridgeCached = invalid
        value = m.top.bridge
        if value = invalid or value = "" then value = "http://127.0.0.1:8090"
        m.bridgeCached = value
    end if
    return m.bridgeCached
end function

sub ShowStatus(text as string)
    if text = ""
        m.status.text = ""
        m.status.visible = false
    else
        m.status.text = text
        m.status.visible = true
    end if
end sub

sub LoadChannels()
    saved = ""
    section = CreateObject("roRegistrySection", "golovinema")
    saved = section.Read("bridge")
    if saved <> "" and saved <> invalid then m.bridgeCached = saved
    data = FetchChannels(m.bridgeCached)
    if data = invalid
        ShowStatus("Bridge unreachable: " + m.bridgeCached + Chr(10) + "Press * (options) to change it.")
        return
    end if
    channels = data.LookupCI("channels")
    if channels = invalid
        ShowStatus("Bridge gave no channels.")
        return
    end if
    content = CreateObject("roSGNode", "ContentNode")
    shown = 0
    for each row in channels
        if row <> invalid
            item = content.CreateChild("ChannelItem")
            header = ""
            number = row.LookupCI("number")
            if number <> invalid then header = number.ToStr() + ". "
            item.title = header + row.LookupCI("name")
            item.Description = row.LookupCI("group")
            item.streamUrl = row.LookupCI("url")
            item.userAgent = row.LookupCI("userAgent")
            item.referrer = row.LookupCI("referrer")
            poster = row.LookupCI("logo")
            if poster <> "" and poster <> invalid then item.HDPosterUrl = poster
            shown = shown + 1
        end if
    end for
    if shown = 0
        ShowStatus("Bridge gave no channels.")
        return
    end if
    m.grid.content = content
    m.grid.visible = true
    m.grid.setFocus(true)
    ShowStatus("")
end sub

function FetchChannels(address as string) as object
    http = CreateObject("roUrlTransfer")
    http.SetUrl(address)
    http.AddHeader("Accept", "application/json")
    http.SetCertificatesFile("common:/certs/ca-bundle.crt")
    json = http.GetToStringWithTimeout(15)
    if json = "" then return invalid
    return ParseJson(json)
end function

sub OnItemSelected()
    if m.grid.itemSelected < 0 then return
    item = m.grid.content.GetChild(m.grid.itemSelected)
    if item = invalid or item.streamUrl = "" then return
    headers = {}
    if item.userAgent <> "" then headers.AddReplace("User-Agent", item.userAgent)
    if item.referrer <> "" then headers.AddReplace("Referer", item.referrer)
    m.player.content = item
    m.player.httpHeaders = headers
    m.current = item
    m.grid.visible = false
    m.player.visible = true
    m.player.control = "play"
end sub

sub OnPlayerState()
    state = m.player.state
    if state = "error"
        ShowStatus("Playback failed for " + m.current.title + Chr(10) + "Geo-blocking is common; try another channel.")
        StopVideo()
    else if state = "finished"
        StopVideo()
    end if
end sub

sub StopVideo()
    m.player.control = "stop"
    m.player.visible = false
    m.player.content = invalid
    m.current = invalid
    m.grid.visible = true
    m.grid.setFocus(true)
end sub

sub OpenSettings()
    dialog = m.top.createChild("KeyboardDialog")
    dialog.title = "Core bridge address"
    dialog.text = BridgeUrl()
    dialog.keyboard.textEditBox.text = BridgeUrl()
    dialog.buttons = ["Save", "Cancel"]
    dialog.observeField("buttonSelected", "OnSettingsDone")
    dialog.setFocus(true)
    m.settings = dialog
end sub

sub OnSettingsDone()
    if m.settings = invalid then return
    text = m.settings.keyboard.textEditBox.text
    choice = m.settings.buttonSelected
    m.settings.close = true
    m.settings = invalid
    if choice = 0 and text <> "" and text <> invalid
        section = CreateObject("roRegistrySection", "golovinema")
        section.Write("bridge", text)
        section.Flush()
        m.bridgeCached = text
        ShowStatus("Saved. Reloading…")
        LoadChannels()
    end if
    m.grid.setFocus(true)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "options" or key = "*"
        if m.player.visible
            StopVideo()
        else if m.settings = invalid
            OpenSettings()
        end if
        return true
    end if
    if key = "back" and m.player.visible
        StopVideo()
        return true
    end if
    return false
end function
