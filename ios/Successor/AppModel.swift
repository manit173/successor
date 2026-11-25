import SwiftUI

/** App-wide state: the loaded engine, the selected tab, and the players gathered for Compare and Squad. */
@MainActor
@Observable
final class AppModel {
    enum Phase { case loading, ready(Engine), failed(String) }
    enum Tab: Hashable { case home, successor, compare, squad, about }

    var phase: Phase = .loading
    var tab: Tab = .home
    /** A player handed to the successor screen (from a profile or the home shortcuts). */
    var successorTarget: Int?
    var compareIds: [Int] = []
    var squadIds: [Int] = []
    /** A profile to open on the Players tab. */
    var openPlayer: Int?
    /** Bumped to ask the squad screen to plan right away. */
    var squadRunToken = 0

    var engine: Engine? { if case .ready(let e) = phase { return e }; return nil }

    /** The real export if this build carries one (generated on the Mac, never committed), else the synthetic sample. */
    nonisolated static func dataDirectory() -> (URL, Bool)? {
        guard let root = Bundle.main.resourceURL?.appendingPathComponent("Data") else { return nil }
        let real = root.appendingPathComponent("real")
        if FileManager.default.fileExists(atPath: real.appendingPathComponent("meta.json").path) { return (real, false) }
        let sample = root.appendingPathComponent("sample")
        if FileManager.default.fileExists(atPath: sample.appendingPathComponent("meta.json").path) { return (sample, true) }
        return nil
    }

    func load() async {
        let result: Result<Engine, Error> = await Task.detached(priority: .userInitiated) {
            guard let (dir, sample) = AppModel.dataDirectory() else {
                return .failure(EngineError.message("No data in this build"))
            }
            return Result { try Engine.load(from: dir, sample: sample) }
        }.value
        switch result {
        case .success(let e):
            phase = .ready(e)
            applyLaunchArguments(e)
        case .failure(let err): phase = .failed(err.localizedDescription)
        }
    }

    /** Demo / screenshot shortcuts: --player=tm:88755, --successor=…, --compare=a,b, --squad=a,b,c, --tab=about. */
    private func applyLaunchArguments(_ e: Engine) {
        var args: [String: String] = [:]
        for a in ProcessInfo.processInfo.arguments where a.hasPrefix("--") {
            let kv = a.dropFirst(2).split(separator: "=", maxSplits: 1).map(String.init)
            args[kv[0]] = kv.count > 1 ? kv[1] : ""
        }
        func ids(_ s: String?) -> [Int] { (s ?? "").split(separator: ",").compactMap { e.playerIndex[String($0)] } }
        if let p = ids(args["player"]).first { openPlayer = p; tab = .home }
        if let p = ids(args["successor"]).first { findSuccessor(of: p) }
        if !ids(args["compare"]).isEmpty { compareIds = ids(args["compare"]); tab = .compare }
        if !ids(args["squad"]).isEmpty { planSquad(ids(args["squad"])) }
        switch args["tab"] {
        case "about": tab = .about
        case "successor": tab = .successor
        default: break
        }
    }

    func findSuccessor(of p: Int) { successorTarget = p; tab = .successor }

    func addToCompare(_ p: Int) {
        if !compareIds.contains(p) { compareIds.append(p) }
        if compareIds.count > 3 { compareIds.removeFirst() }
        tab = .compare
    }

    func planSquad(_ ids: [Int]) {
        squadIds = ids
        squadRunToken += 1
        tab = .squad
    }

    func addToSquad(_ p: Int) {
        if !squadIds.contains(p) && squadIds.count < 8 { squadIds.append(p) }
        tab = .squad
    }
}
