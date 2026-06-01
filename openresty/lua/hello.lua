local cjson = require "cjson.safe"

ngx.header["Content-Type"] = "application/json; charset=utf-8"

local payload = {
    ok = true,
    message = "hello openresty",
    method = ngx.req.get_method(),
    uri = ngx.var.uri,
    time = ngx.localtime()
}

ngx.say(cjson.encode(payload))
