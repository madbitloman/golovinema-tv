' Entry point: expose the manifest-configured bridge URL and wait on the message loop.

sub Main()
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    scene = screen.CreateScene("ChannelScene")
    info = CreateObject("roAppInfo")
    bridge = info.GetValue("bridge")
    if bridge = invalid or bridge = "" then bridge = ""
    scene.AddField("bridge", "string", true)
    scene.bridge = bridge
    screen.Show()
    while true
        msg = WaitObject(port)
        if type(msg) = "roSGScreenEvent"
            if msg.IsScreenClosed() then exit while
        end if
    end while
end sub
