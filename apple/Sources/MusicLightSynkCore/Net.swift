import Foundation
#if canImport(Darwin)
import Darwin
#endif

// Small networking helpers: UDP (WLED realtime, device link), blocking HTTP calls for background
// queues, and a tiny HTTP server for Home Assistant's Wi-Fi control. Plain BSD sockets so
// broadcast works and nothing depends on a run loop.

public final class UdpSocket {
    private let fd: Int32

    /// [bindPort]: receive on that port (nil = send only). [broadcast]: allow sending to broadcast addresses.
    public init?(bindPort: UInt16? = nil, broadcast: Bool = false, receiveTimeoutMs: Int = 500) {
        let s = socket(AF_INET, SOCK_DGRAM, Int32(IPPROTO_UDP))
        if s < 0 { return nil }
        var yes: Int32 = 1
        let len = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, len)
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, len)
        if broadcast { setsockopt(s, SOL_SOCKET, SO_BROADCAST, &yes, len) }
        var tv = timeval(tv_sec: receiveTimeoutMs / 1000, tv_usec: Int32((receiveTimeoutMs % 1000) * 1000))
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        if let port = bindPort {
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = port.bigEndian
            addr.sin_addr.s_addr = in_addr_t(0)
            let r = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            if r != 0 {
                Darwin.close(s)
                return nil
            }
        }
        fd = s
    }

    deinit { Darwin.close(fd) }

    /// The port actually bound (useful with port 0).
    public var localPort: UInt16 {
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        return UInt16(bigEndian: addr.sin_port)
    }

    @discardableResult
    public func send(_ bytes: [UInt8], to addr: sockaddr_in) -> Bool {
        var a = addr
        let n = bytes.withUnsafeBytes { buf in
            withUnsafePointer(to: &a) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, buf.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        return n == bytes.count
    }

    /// Blocks up to the receive timeout. nil on timeout.
    public func receive(maxBytes: Int = 8192) -> (bytes: [UInt8], from: sockaddr_in)? {
        var buf = [UInt8](repeating: 0, count: maxBytes)
        var from = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = buf.withUnsafeMutableBytes { b in
            withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, b.baseAddress, maxBytes, 0, $0, &len) }
            }
        }
        if n <= 0 { return nil }
        return (Array(buf[0..<n]), from)
    }

    /// IPv4 address for a host name or dotted address ("host:port" -> host part).
    public static func resolve(_ host: String, port: UInt16) -> sockaddr_in? {
        let name = String(host.split(separator: ":").first ?? Substring(host))
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(name, nil, &hints, &res) == 0, let first = res else { return nil }
        defer { freeaddrinfo(res) }
        guard let sa = first.pointee.ai_addr else { return nil }
        var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        addr.sin_port = port.bigEndian
        return addr
    }

    public static func describe(_ a: sockaddr_in) -> String {
        var addr = a.sin_addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN))
        return String(cString: buf)
    }

    /// Broadcast addresses of this machine's IPv4 interfaces, plus 255.255.255.255.
    public static func broadcastAddresses(port: UInt16) -> [sockaddr_in] {
        var out: [sockaddr_in] = []
        var ifap: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifap) == 0, let first = ifap {
            var p: UnsafeMutablePointer<ifaddrs>? = first
            while let i = p {
                let flags = Int32(i.pointee.ifa_flags)
                if let a = i.pointee.ifa_addr, a.pointee.sa_family == sa_family_t(AF_INET),
                   flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, flags & IFF_BROADCAST != 0,
                   let d = i.pointee.ifa_dstaddr {
                    var b = d.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                    b.sin_port = port.bigEndian
                    out.append(b)
                }
                p = i.pointee.ifa_next
            }
            freeifaddrs(ifap)
        }
        if let all = resolve("255.255.255.255", port: port) { out.append(all) }
        return out
    }

    /// This machine's LAN IPv4 address (Wi-Fi / Ethernet), or nil.
    public static func lanAddress() -> String? {
        var ifap: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifap) == 0, let first = ifap else { return nil }
        defer { freeifaddrs(ifap) }
        var best: String?
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let i = p {
            let flags = Int32(i.pointee.ifa_flags)
            if let a = i.pointee.ifa_addr, a.pointee.sa_family == sa_family_t(AF_INET), flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 {
                let sin = a.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                let ip = describe(sin)
                let name = String(cString: i.pointee.ifa_name)
                let priv = ip.hasPrefix("192.168.") || ip.hasPrefix("10.") || (ip.hasPrefix("172.") && (16...31).contains(Int(ip.split(separator: ".")[1]) ?? 0))
                if priv && (best == nil || name.hasPrefix("en")) { best = ip }
            }
            p = i.pointee.ifa_next
        }
        return best
    }
}

/// Blocking HTTP for background queues (never call on the main thread).
public enum Http {
    public struct Result {
        public var ok: Bool
        public var code: Int
        public var message: String
        public var body: Data?
    }

    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 5
        c.timeoutIntervalForResource = 8
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    public static func request(_ url: String, method: String = "GET", json: Any? = nil, bearer: String? = nil, timeout: TimeInterval = 5) -> Result {
        guard let u = URL(string: url) else { return Result(ok: false, code: -1, message: "bad URL", body: nil) }
        var req = URLRequest(url: u, timeoutInterval: timeout)
        req.httpMethod = method
        if let json {
            req.httpBody = try? JSONSerialization.data(withJSONObject: json)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        if let bearer, !bearer.isEmpty { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        let done = DispatchSemaphore(value: 0)
        var result = Result(ok: false, code: -1, message: "timed out", body: nil)
        session.dataTask(with: req) { data, resp, err in
            if let err {
                result = Result(ok: false, code: -1, message: err.localizedDescription, body: nil)
            } else if let http = resp as? HTTPURLResponse {
                let hint = http.statusCode == 404 ? " (wrong URL?)" : http.statusCode == 401 ? " (token rejected)" : ""
                result = Result(ok: (200..<300).contains(http.statusCode), code: http.statusCode, message: "HTTP \(http.statusCode)\(hint)", body: data)
            }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + timeout + 1)
        return result
    }

    public static func getJson(_ url: String, timeout: TimeInterval = 3) -> [String: Any]? {
        let r = request(url, timeout: timeout)
        guard r.ok, let d = r.body else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }
}

/// Home Assistant's Wi-Fi control (same API as the Android app's ControlServer):
/// POST /api/{configure,test,status,capture,stop_capture} with "Authorization: Bearer <token>";
/// GET /api/ping without. [handle] runs on a background queue and returns a message.
public final class ControlServer {
    public static let port: UInt16 = 21331
    public static let actions: Set<String> = ["configure", "test", "status", "capture", "stop_capture"]
    private var fd: Int32 = -1
    private let token: () -> String
    private let handle: (String, [String: Any]) -> String
    private let queue = DispatchQueue(label: "mls.control", attributes: .concurrent)

    public init(token: @escaping () -> String, handle: @escaping (String, [String: Any]) -> String) {
        self.token = token
        self.handle = handle
    }

    public var isRunning: Bool { fd >= 0 }

    public func start(port: UInt16 = ControlServer.port) -> Bool {
        if fd >= 0 { return true }
        let s = socket(AF_INET, SOCK_STREAM, 0)
        if s < 0 { return false }
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = in_addr_t(0)
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        } == 0 && listen(s, 8) == 0
        if !ok {
            Darwin.close(s)
            return false
        }
        fd = s
        Thread.detachNewThread { [weak self] in self?.acceptLoop(s) }
        return true
    }

    public func stop() {
        if fd >= 0 { Darwin.close(fd) }
        fd = -1
    }

    private func acceptLoop(_ s: Int32) {
        while fd == s {
            let c = accept(s, nil, nil)
            if c < 0 { if fd != s { return } else { continue } }
            queue.async { [weak self] in self?.serve(c) }
        }
    }

    private func serve(_ c: Int32) {
        defer { Darwin.close(c) }
        var tv = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var data = [UInt8]()
        var buf = [UInt8](repeating: 0, count: 4096)
        var headerEnd: Int?
        var contentLength = 0
        while data.count < 70_000 {
            let n = buf.withUnsafeMutableBytes { recv(c, $0.baseAddress, 4096, 0) }
            if n <= 0 { break }
            data += buf[0..<n]
            if headerEnd == nil, let r = ControlServer.find(data, [13, 10, 13, 10]) {
                headerEnd = r + 4
                let head = String(decoding: data[0..<r], as: UTF8.self)
                for line in head.split(separator: "\r\n") where line.lowercased().hasPrefix("content-length:") {
                    contentLength = Int(line.split(separator: ":")[1].trimmingCharacters(in: .whitespaces)) ?? 0
                }
            }
            if let h = headerEnd, data.count >= h + contentLength { break }
        }
        guard let h = headerEnd else { return }
        let head = String(decoding: data[0..<(h - 4)], as: UTF8.self)
        let lines = head.split(separator: "\r\n").map(String.init)
        let request = lines.first?.split(separator: " ").map(String.init) ?? []
        guard request.count >= 2 else { return reply(c, 400, "bad request") }
        let method = request[0], path = request[1].split(separator: "?").first.map(String.init) ?? ""
        let auth = lines.first { $0.lowercased().hasPrefix("authorization:") }.map { String($0.dropFirst("authorization:".count)).trimmingCharacters(in: .whitespaces) } ?? ""
        if method == "GET" && path == "/api/ping" { return reply(c, 200, "ok", extra: ["app": "music_light_synk"]) }
        let expected = token()
        guard !expected.isEmpty, auth == "Bearer \(expected)" else { return reply(c, 401, "missing or wrong token") }
        guard method == "POST", path.hasPrefix("/api/") else { return reply(c, 404, "not found") }
        let action = String(path.dropFirst(5))
        guard ControlServer.actions.contains(action) else { return reply(c, 404, "unknown action \(action)") }
        let bodyBytes = Data(data[h..<min(data.count, h + contentLength)])
        var body: [String: Any] = [:]
        if !bodyBytes.isEmpty {
            guard let obj = (try? JSONSerialization.jsonObject(with: bodyBytes)) as? [String: Any] else { return reply(c, 400, "body must be a JSON object") }
            body = obj
        }
        reply(c, 200, handle(action, body))
    }

    private func reply(_ c: Int32, _ code: Int, _ message: String, extra: [String: Any] = [:]) {
        var obj = extra
        obj["ok"] = code == 200
        obj["message"] = message
        let body = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
        let reason = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found"][code] ?? "Error"
        var out = Data("HTTP/1.1 \(code) \(reason)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n".utf8)
        out.append(body)
        out.withUnsafeBytes { _ = Darwin.send(c, $0.baseAddress, out.count, 0) }
    }

    static func find(_ hay: [UInt8], _ needle: [UInt8]) -> Int? {
        guard hay.count >= needle.count else { return nil }
        for i in 0...(hay.count - needle.count) where Array(hay[i..<(i + needle.count)]) == needle { return i }
        return nil
    }
}
