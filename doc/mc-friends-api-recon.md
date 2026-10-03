# Minecraft Java 版「好友功能」API 逆向侦察报告

侦察对象（本机实测，非网上推测）：

| 项目 | 路径 / 版本 |
| --- | --- |
| 客户端 | `D:\Minecraft\.minecraft\versions\26.3\26.3.jar`（版本号 26.3，主类 `net.minecraft.client.main.Main`） |
| authlib | `D:\Minecraft\.minecraft\libraries\com\mojang\authlib\10.0.77\authlib-10.0.77.jar` |
| 启动器 | PCL（`D:\Minecraft\.minecraft\PCL.ini`） |
| Wireshark | `D:\Software\Wireshark\Wireshark.exe` |
| JDK（用于 javap） | `D:\jdk\jdk-25.0.4.101-hotspot\bin\javap.exe` |

结论：**可行，而且比 authlib-injector 更容易 —— 网络层重定向不需要改字节码，一个 JVM 系统属性就能接管全部服务端点。**

---

## 1. 好友功能在代码里的落点

客户端 UI 层（明文类名，未混淆）：

```
net/minecraft/client/gui/components/FriendsButton.class
net/minecraft/client/gui/components/toasts/FriendToast.class
net/minecraft/client/gui/screens/friends/*            （AddFriendWidget / FriendEntry / FriendsOverlayScreen / FriendsTab / PendingTab ...）
net/minecraft/client/gui/screens/social/PlayerSocialManager.class
net/minecraft/client/gui/screens/social/PresenceHandler.class
net/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler.class
```

真正的网络层**全部在 authlib 里**，不在客户端 jar 中：

```
com/mojang/authlib/services/FriendsService.class                     (接口)
com/mojang/authlib/services/MinecraftServicesFriendsService.class    (HTTP 实现)
com/mojang/authlib/services/MinecraftServicesDiscoveryService.class  (端点发现)
com/mojang/authlib/services/MinecraftServicesUserApiService.class    (属性/开关)
com/mojang/authlib/services/response/{FriendData,FriendsListResponse,FriendDto,PresenceResponse,PresenceStatusDto,PresenceStatus}.class
com/mojang/authlib/services/request/{FriendActionRequest,UpdateType,PresenceRequest,UserAttributesRequest}.class
```

客户端接线（`net.minecraft.client.Minecraft` 构造器字节码）：

```
MinecraftServicesDiscoveryService.create(Proxy, boolean)      // 注意是 2 参重载
  -> Services.create(discoveryService, gameDirectory)
  -> createUserApiService(discoveryService, gameConfig)
  -> discoveryService.createFriendsService(user.getAccessToken())
  -> new RemoteFriendListUpdateHandler(friendsService, this)
  -> new PlayerSocialManager(this, userApiService, friendsService, remoteFriendListUpdateHandler)
```

好友按钮/列表是否出现，取决于两个开关（`Minecraft.friendsEnabled()` / `allowFriendRequests()`）：

```
friendsEnabled()      = userProperties().flag(UserFlag.FRIENDS_ENABLED)     && !offlineDeveloperMode
allowFriendRequests() = userProperties().flag(UserFlag.ACCEPT_FRIEND_INVITES)
```

`UserProperties` 来自 `GET player/getAttributes`（`/player/attributes`）→ `UserAttributesResponse.friendsPreferences{friends,acceptInvites}`（枚举 `ToggleValue.ENABLED|DISABLED`）。

---

## 2. 关键：端点不是硬编码，而是 discovery 文档下发

`MinecraftServicesEnvironment` 里只有 discovery 入口：

```
PROD    -> https://discovery.minecraftservices.com/minecraft/client
STAGING -> https://discovery-staging.minecraftservices.com/minecraft/client
```

`https://discovery.minecraftservices.com/minecraft/client` 是**公开、免鉴权**的 JSON，内容（实测抓取）：

```json
{
  "environment": "prod",
  "product": "minecraft",
  "discovery": {
    "authentication": { "endpoints": {
      "getPublicKeys": { "uri": "https://api.minecraftservices.com/publickeys" },
      "loginXbox":     { "uri": "https://api.minecraftservices.com/authentication/login_with_xbox" } } },
    "session": { "endpoints": {
      "getProfileById": { "uri": "https://sessionserver.mojang.com/session/minecraft/profile/{profileId}" },
      "verify":         { "uri": "https://sessionserver.mojang.com/session/minecraft/hasJoined" },
      "join":           { "uri": "https://sessionserver.mojang.com/session/minecraft/join" } } },
    "player": { "endpoints": {
      "updatePresence":   { "uri": "https://api.minecraftservices.com/presence" },
      "sendReport":       { "uri": "https://api.minecraftservices.com/player/report" },
      "getAttributes":    { "uri": "https://api.minecraftservices.com/player/attributes" },
      "getFriends":       { "uri": "https://api.minecraftservices.com/friends" },
      "getCertificates":  { "uri": "https://api.minecraftservices.com/player/certificates" },
      "updateAttributes": { "uri": "https://api.minecraftservices.com/player/attributes" },
      "updateFriends":    { "uri": "https://api.minecraftservices.com/friends" },
      "getBlocklist":     { "uri": "https://api.minecraftservices.com/privacy/blocklist" } } },
    "profiles": { "endpoints": {
      "getManyByName": { "uri": "https://api.mojang.com/profiles/minecraft" },
      "getByName":     { "uri": "https://api.mojang.com/users/profiles/minecraft/{name}" },
      "getTexture":    { "validUris": [ "https://textures.minecraft.net/texture/{textureId}",
                                        "http://textures.minecraft.net/texture/{textureId}" ] } } },
    "telemetry": { "endpoints": { "sendEvents": { "uri": "https://api.minecraftservices.com/events" } } }
  }
}
```

解析类：`DiscoveryResponse{environment, product, discovery}`、`Discovery{product, authentication, session, player, profiles, telemetry}`、`Endpoints{endpoints: Map<String,Endpoint>}`、`Endpoint{uri, validUris}`；`Service` 枚举 = `AUTHENTICATION, SESSION, PLAYER, PROFILES, TELEMETRY`。

### 注入点：`-Dminecraft.api.discovery.host`

`EnvironmentParser`（authlib 10.0.77）字节码：

```
getEnvironmentFromProperties():
    if (environmentOverride != null) s = environmentOverride;
    else                             s = System.getProperty("minecraft.api.env");
    return MinecraftServicesEnvironment.fromString(s)     // 只认 prod/staging
             .orElseGet(EnvironmentParser::fromHostNames);

fromHostNames():
    host = System.getProperty("minecraft.api.discovery.host");
    if (host != null) return Optional.of(new Environment(host, "properties"));
    return Optional.empty();
```

`MinecraftServicesDiscoveryService.determineEnvironment()` = `getEnvironmentFromProperties().orElse(PROD.getEnvironment())`，
`create(Proxy, boolean)` → `determineEnvironment()` → 因此客户端走的就是这条属性路径。

而 `HttpDiscoveryService.constantURL(String)` 就是裸的 `new URL(s)`，**不校验协议、不限定域名**；`MinecraftClient.createUrlConnection()` 也只是
`url.openConnection(proxy)` + 超时设置，**没有自定义 SSLContext、没有 HostnameVerifier、没有证书固定**。

推论：

1. `-Dminecraft.api.discovery.host=<完整 URL>` 可以把你自己的 discovery 文档塞给客户端；
2. 该文档里把 `player.getFriends / updateFriends / updatePresence` 指向你自己的服务，其余（认证、会话、皮肤、证书、遥测）保持 Mojang 原样，即可实现"只换好友后端"；
3. 支持 `http://`（本机明文，最省事）；
4. 不要同时设置 `minecraft.api.env`，否则会优先走 prod/staging 分支；
5. 格式必须是完整 URL（`http://127.0.0.1:8787/minecraft/client`），否则 `new URL()` 抛 MalformedURLException 被包成 `Error`，客户端直接崩。

---

## 3. 需要实现的线上协议（全部由字节码确定）

所有请求都带 `Authorization: Bearer <Minecraft access token>`（客户端把 `User.getAccessToken()` 原样交给 `FriendsService`），
`Content-Type: application/json; charset=utf-8`，HTTP 栈是 `HttpURLConnection`。JSON 字段名来自 Gson `@SerializedName`，已核对。

### 3.1 拉好友列表

```
GET  {getFriends}
Header: If-None-Match: <上次的 ETag>        （首次不带）
200  { "friends":           [ {"profileId":"<uuid>","name":"..."} ],
       "incomingRequests":  [ ... ],
       "outgoingRequests":  [ ... ] }
Header: ETag: <token>
Header: Retry-After: <秒>                    （服务器控制轮询间隔，客户端 parseRetryAfter 解析）
304  未变化（客户端沿用缓存）
```

失败码映射到 `FriendsService.ResultCode`：429 → `TOO_MANY_REQUESTS`，403 → `FORBIDDEN`，401 → `UNAUTHORIZED`，5xx → `TEMPORARY_UNAVAILABLE/SERVICE_NOT_AVAILABLE`，404/未知 → `UNKNOWN_PROFILE` 等。

### 3.2 加/删/接受/拒绝/撤回（全部同一个 PUT）

```
PUT  {updateFriends}
{ "name": "Dinnerbone", "updateType": "ADD" }        // 按名字加好友 / 发请求
{ "profileId": "<uuid>", "updateType": "ADD" }       // 接受收到的请求（accept 走 ADD）
{ "profileId": "<uuid>", "updateType": "REMOVE" }    // 删好友 / 拒绝请求 / 撤回发出的请求
200  -> 与 3.1 同结构的新列表
```

语义映射（字节码确认）：`removeFriend`=REMOVE byId，`acceptIncomingFriendRequest`=ADD byId，
`declineIncomingFriendRequest`=REMOVE byId，`sendFriendRequest`=ADD（name 或 id），`revokeOutgoingFriendRequest`=REMOVE byId。
客户端在成功后会本地加 10 秒冷却（`REQUEST_COOLDOWN_SECONDS = 10`）并清空 friends ETag。

### 3.3 Presence（注意是 POST，不是 GET）

```
POST {updatePresence}
Header: If-None-Match: <presence ETag>
{ "status": "ONLINE" }        // 上报自己的状态
200  { "presence": [ { "profileId":"<uuid>", "pmid":"<uuid>", "status":"<enum>", "lastUpdated":"<instant>" } ] }
Header: ETag / Retry-After
304  -> 无 body，沿用缓存
```

`PresenceStatus` 枚举：`ONLINE, PLAYING_OFFLINE, PLAYING_REALMS, PLAYING_SERVER, PLAYING_HOSTED_SERVER, OFFLINE`。
客户端行为：进入多人游戏 / 单人世界时 `tryUpdatePresence()`，退出时 `sendOfflinePresence()`，`PresenceHandler.tick()` 按间隔轮询。

### 3.4 开关（可选，用于强制打开功能）

```
GET  {getAttributes}   -> UserAttributesResponse
     { "privileges": { "<name>": { "enabled": true }, ... },
       "friendsPreferences": { "friends": "ENABLED", "acceptInvites": "ENABLED" },
       "profanityFilterPreferences": {...}, "chatPreferences": {...}, "banStatus": {...} }
PUT  {updateAttributes}  <- UserAttributesRequest{ profanityFilterPreferences, friendsPreferences }
```

`updateFriendSettings(boolean,boolean)` 走的就是这个（`FriendsPreferences{ToggleValue, ToggleValue}`）。
建议做法：把你的服务作为反向代理转发到真实 `https://api.minecraftservices.com/player/attributes`，只把 `friendsPreferences` 改写为 ENABLED，避免影响聊天签名/举报/封禁状态。

---

## 4. 关于 Wireshark：能抓，但抓不到明文（除非换路子）

本机装了 Wireshark（`D:\Software\Wireshark\Wireshark.exe`），但要注意：

1. 好友 API 全是 HTTPS，**Java（JSSE）不支持 `SSLKEYLOGFILE`**，所以单纯挂 Wireshark 只能看到 TLS 记录，看不到 API 内容。
2. authlib 的 HTTP 栈是 `URL.openConnection(proxy)`，proxy 来自启动参数
   `--proxyHost/--proxyPort/--proxyUser/--proxyPass`，且构造为 **`Proxy.Type.SOCKS`**（`Main.class` 字节码确认），没有代理时是 `Proxy.NO_PROXY`。
   这意味着 `-Dhttps.proxyHost` 这类系统属性**会被绕过**（显式传入 Proxy 对象）。
   - 想 MITM：把 mitmproxy 跑成 SOCKS 模式（`mitmproxy --mode socks5 -p 1080`），启动参数加 `--proxyHost 127.0.0.1 --proxyPort 1080`，
     并让游戏 JVM 信任 mitmproxy 的 CA（`-Djavax.net.ssl.trustStore=...` 指向含该 CA 的 JKS/PKCS12，或导入运行时 cacerts）。
   - 或者用 `-javaagent` 形式的 JSSE keylog agent 导出 NSS keylog，再在 Wireshark 里配 (Pre)-Master-Secret 解密。
3. **推荐路线（也是本方案自带的调试能力）**：既然发现入口可改，直接
   `-Dminecraft.api.discovery.host=http://127.0.0.1:8787/minecraft/client`，
   所有好友请求就以**明文 HTTP**发到本机，用 Npcap 的 loopback 适配器抓包或直接看你自己的服务日志 —— 比 MITM 简单且能看到 `Authorization` 头。
   这条路线同时就是 friendlib-injector 的运行形态。

抓包在本项目里的真实价值：验证是否还有 discovery 文档之外的其它通道（本次静态侦察未发现 P2P/WebRTC/signaling 相关类，
但 `PresenceStatus` 里存在 `PLAYING_HOSTED_SERVER`，说明"托管世界/直连好友"可能走 Realms 或别的链路，需要实测确认边界）。

---

## 5. 实现方案

### 方案 A（推荐）：discovery 重定向 + 自建服务，零字节码

1. 起一个 HTTP(S) 服务，暴露 `GET /minecraft/client` 返回改造过的 discovery JSON
   （只改 `player.getFriends / updateFriends / updatePresence` 的 uri，可选加 `getAttributes`）。
2. 实现 `GET /friends`、`PUT /friends`、`POST /presence`（+ 可选 `GET|PUT /player/attributes` 代理）。
3. 身份识别：进来的 `Authorization: Bearer` 是**真实 Minecraft access token**（来自 `login_with_xbox`）。
   - 简单做法：把它转发给 `https://api.minecraftservices.com/minecraft/profile` 验证并拿到 `{id,name}`，按 token 缓存几分钟；
   - 严谨做法：用 discovery 的 `getPublicKeys`（`https://api.minecraftservices.com/publickeys`）验签 JWT，离线校验。
   这样"第三方好友功能"仍然绑定真实 MC 账号，不需要自己做一套账号体系。
4. 启动器里加 JVM 参数：`-Dminecraft.api.discovery.host=http://<你的地址>/minecraft/client`（PCL：版本设置 → 高级 → JVM 参数）。
5. 所有想互相加好友的玩家都要加这个参数（或分发改好的启动配置），因为你替换的是一整条后端。

优点：纯官方扩展点、随版本升级基本不坏（除非 Mojang 改 discovery 协议）、不需要 ASM、不需要处理混淆。
缺点：依赖启动器能传 JVM 参数；分发给"不会改参数"的用户不如一个 jar 方便。

### 方案 B：authlib-injector 式 `-javaagent`

只有当你要做"一个 jar 丢进去就生效（含启动器自动注入）"、或要接管 `EnvironmentParser`/强制 `FRIENDS_ENABLED` 时才需要。

最小实现 = 用 ASM transform 一个方法：

```
com.mojang.authlib.EnvironmentParser.getEnvironmentFromProperties()
    -> 直接 return Optional.of(new Environment("http://<你的地址>/minecraft/client", "friendlib"))
```

（或 transform `MinecraftServicesDiscoveryService.getUrl` / `MinecraftServicesFriendsService` 的取址逻辑。）
参考先例：本机 `authlib-injector.jar` 里已有 `moe/yushi/authlibinjector/transform/support/SkinWhitelistTransformUnit`
引用了 `com.mojang.authlib.services.MinecraftServicesDiscoveryService`，说明这类改法在同一套类上是可行且有人做过的。

---

## 6. 风险 / 边界

- **开关 gating**：若账号的 `FRIENDS_ENABLED` 为 false，UI 不会出现好友入口。此时必须重定向 `getAttributes` 并返回 ENABLED（建议代理真实响应后只改 friends 部分）。
- **好友之间的"加入世界"**：`updatePresence` 只提供状态（含 `PLAYING_HOSTED_SERVER`），discovery 文档里没有 P2P/直连端点；加入托管世界大概率仍走 Realms / 服务器列表链路，属于本方案的边界，需要实测确认。
- **客户端校验**：好友 UI、toast、presence 都读 `FriendsService.ResultCode`，自建服务必须尽量映射正确的 HTTP 状态码，否则 UI 会弹错误提示。
- **协议稳定性**：单个版本（26.3 / authlib 10.0.77）已实测；跨版本字段可能变化，建议实现里对未知字段宽容（Gson 默认忽略未知字段，反向也一样）。
- **合规**：这是修改自己客户端的网络端点、用于私人和小圈子玩法，与 authlib-injector 同类做法；不要用改后的客户端冒充分发、或替他人绕过官方服务限制。

---

## 7. 复现侦察的命令（本机可直接跑）

```powershell
$javap = "D:\jdk\jdk-25.0.4.101-hotspot\bin\javap.exe"
$authlib = "D:\Minecraft\.minecraft\libraries\com\mojang\authlib\10.0.77\authlib-10.0.77.jar"
$client = "D:\Minecraft\.minecraft\versions\26.3\26.3.jar"

# 好友网络层全貌
& $javap -p -cp $authlib com.mojang.authlib.services.FriendsService
& $javap -p -cp $authlib com.mojang.authlib.services.MinecraftServicesFriendsService
& $javap -p -cp $authlib com.mojang.authlib.services.MinecraftServicesDiscoveryService
& $javap -p -cp $authlib com.mojang.authlib.EnvironmentParser
& $javap -c -p -cp $authlib com.mojang.authlib.EnvironmentParser        # 看 minecraft.api.discovery.host

# 客户端接线与开关
& $javap -c -p -cp $client net.minecraft.client.Minecraft | Select-String "MinecraftServicesDiscoveryService|createFriendsService" -Context 4,12
& $javap -c -p -cp $client net.minecraft.client.main.Main   | Select-String "proxyHost|Proxy" -Context 2,6

# discovery 文档（公开免鉴权）
Invoke-RestMethod https://discovery.minecraftservices.com/minecraft/client | ConvertTo-Json -Depth 8
```
