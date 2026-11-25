import Foundation

// Ports of the Java SquadPlanner, BipartiteMatcher (with its Edmonds–Karp MaxFlow), Comparer and PlayerSearch.

// MARK: - Matching

struct Edge: Sendable { let left: Int; let right: Int; let score: Double }

struct Matching: Sendable { let rightOf: [Int]; let size: Int; let bottleneck: Double }

/** Edmonds–Karp maximum flow on adjacency lists, in the same edge order as the Java version. */
struct MaxFlow {
    private var head: [Int]
    private var next: [Int] = []
    private var to: [Int] = []
    private(set) var cap: [Int] = []

    init(vertices: Int) { head = [Int](repeating: -1, count: vertices) }

    @discardableResult
    mutating func addEdge(_ u: Int, _ v: Int, _ c: Int) -> Int {
        let id = to.count
        link(u, v, c)
        link(v, u, 0)
        return id
    }

    private mutating func link(_ u: Int, _ v: Int, _ c: Int) {
        to.append(v); cap.append(c); next.append(head[u]); head[u] = to.count - 1
    }

    mutating func maxFlow(_ s: Int, _ t: Int) -> Int {
        var flow = 0
        let n = head.count
        var parent = [Int](repeating: -1, count: n)
        var queue = [Int](repeating: 0, count: n)
        while true {
            for i in 0..<n { parent[i] = -1 }
            parent[s] = -2
            var qh = 0, qt = 0
            queue[qt] = s; qt += 1
            while qh < qt && parent[t] == -1 {
                let u = queue[qh]; qh += 1
                var e = head[u]
                while e != -1 {
                    let v = to[e]
                    if cap[e] > 0 && parent[v] == -1 { parent[v] = e; queue[qt] = v; qt += 1 }
                    e = next[e]
                }
            }
            if parent[t] == -1 { return flow }
            var b = Int.max
            var v = t
            while v != s { b = min(b, cap[parent[v]]); v = to[parent[v] ^ 1] }
            v = t
            while v != s { cap[parent[v]] -= b; cap[parent[v] ^ 1] += b; v = to[parent[v] ^ 1] }
            flow += b
        }
    }

    func flowOn(_ edge: Int) -> Int { cap[edge ^ 1] }
}

enum Matcher {
    static func minScore(_ rightOf: [Int], _ edges: [Edge]) -> Double {
        var m = Double.nan
        for e in edges where rightOf[e.left] == e.right { m = m.isNaN ? e.score : min(m, e.score) }
        return m
    }

    /** Max-flow over edges whose ends are both free; writes the new pairs; returns how many were added. */
    static func augment(_ l: Int, _ r: Int, _ edges: [Edge], _ rightOf: inout [Int], _ taken: inout [Bool]) -> Int {
        if edges.isEmpty { return 0 }
        let s = l + r, t = s + 1
        var f = MaxFlow(vertices: l + r + 2)
        var leftUsed = [Bool](repeating: false, count: l)
        var rightUsed = [Bool](repeating: false, count: r)
        var ids = [Int](repeating: -1, count: edges.count)
        for (i, e) in edges.enumerated() {
            if rightOf[e.left] != -1 || taken[e.right] { continue }
            ids[i] = f.addEdge(e.left, l + e.right, 1)
            leftUsed[e.left] = true
            rightUsed[e.right] = true
        }
        for i in 0..<l where leftUsed[i] { f.addEdge(s, i, 1) }
        for j in 0..<r where rightUsed[j] { f.addEdge(l + j, t, 1) }
        let added = f.maxFlow(s, t)
        for (i, e) in edges.enumerated() where ids[i] >= 0 && f.flowOn(ids[i]) == 1 {
            rightOf[e.left] = e.right
            taken[e.right] = true
        }
        return added
    }

    static func tiered(_ l: Int, _ r: Int, _ edges: [Edge], _ thresholds: [Double]) -> Matching {
        var rightOf = [Int](repeating: -1, count: l)
        var taken = [Bool](repeating: false, count: r)
        var size = 0
        for t in thresholds.sorted().reversed() {
            let tier = edges.filter { $0.score >= t && rightOf[$0.left] == -1 && !taken[$0.right] }
            size += augment(l, r, tier, &rightOf, &taken)
        }
        return Matching(rightOf: rightOf, size: size, bottleneck: minScore(rightOf, edges))
    }

    /** Every left vertex matched, maximising the weakest pair; strong pairs preferred among the optima. Nil if infeasible. */
    static func bottleneck(_ l: Int, _ r: Int, _ edges: [Edge]) -> Matching? {
        if l == 0 { return Matching(rightOf: [], size: 0, bottleneck: .nan) }
        let scores = Array(Set(edges.map(\.score))).sorted()
        var lo = 0, hi = scores.count - 1
        var tau = Double.nan
        while lo <= hi {
            let mid = (lo + hi) >> 1
            var rightOf = [Int](repeating: -1, count: l)
            var taken = [Bool](repeating: false, count: r)
            if augment(l, r, edges.filter { $0.score >= scores[mid] }, &rightOf, &taken) == l {
                tau = scores[mid]; lo = mid + 1
            } else { hi = mid - 1 }
        }
        if tau.isNaN { return nil }
        let best = preferStrong(l, r, edges, tau)
        return Matching(rightOf: best, size: l, bottleneck: minScore(best, edges))
    }

    private static func preferStrong(_ l: Int, _ r: Int, _ edges: [Edge], _ tau: Double) -> [Int] {
        let sorted = edges.filter { $0.score >= tau }.stableSorted { $0.score > $1.score }
        var adj = [[Int]](repeating: [], count: l)
        var rightOf = [Int](repeating: -1, count: l)
        var leftOf = [Int](repeating: -1, count: r)
        var seen = [Int](repeating: 0, count: r)
        var stamp = 0
        var k = 0
        while k < sorted.count {
            let level = sorted[k].score
            while k < sorted.count && sorted[k].score == level { adj[sorted[k].left].append(sorted[k].right); k += 1 }
            for i in 0..<l where rightOf[i] == -1 {
                stamp += 1
                _ = augmentFrom(i, adj, &rightOf, &leftOf, &seen, stamp)
            }
        }
        return rightOf
    }

    private static func augmentFrom(_ u: Int, _ adj: [[Int]], _ rightOf: inout [Int], _ leftOf: inout [Int],
                                    _ seen: inout [Int], _ stamp: Int) -> Bool {
        for v in adj[u] where leftOf[v] == -1 && seen[v] != stamp {
            seen[v] = stamp; rightOf[u] = v; leftOf[v] = u
            return true
        }
        for v in adj[u] {
            if seen[v] == stamp { continue }
            seen[v] = stamp
            if leftOf[v] == -1 || augmentFrom(leftOf[v], adj, &rightOf, &leftOf, &seen, stamp) {
                rightOf[u] = v; leftOf[v] = u
                return true
            }
        }
        return false
    }

    static func greedy(_ l: Int, _ r: Int, _ edges: [Edge]) -> Matching {
        var rightOf = [Int](repeating: -1, count: l)
        var taken = [Bool](repeating: false, count: r)
        var size = 0
        for e in edges.stableSorted(by: { $0.score > $1.score }) where rightOf[e.left] == -1 && !taken[e.right] {
            rightOf[e.left] = e.right; taken[e.right] = true; size += 1
        }
        return Matching(rightOf: rightOf, size: size, bottleneck: minScore(rightOf, edges))
    }
}

// MARK: - Squad planner

struct Assignment: Sendable, Identifiable {
    let departure: Int; let position: Int; let role: RoleDef
    let candidate: Int?; let age: Int; let candidatePosition: Int?; let club: String?; let league: Int?
    let similarity: Double; let positionNote: String?; let leagueNote: String?
    var id: Int { departure }
}

struct SquadPlan: Sendable {
    let matching: [Assignment]; let weakest: Double; let greedy: [Assignment]; let greedyWeakest: Double
    let unfilled: [Int]; let candidates: Int; let edges: Int; let nextSeason: String; let perRole: Int
}

extension Engine {
    func plan(departures: [Int], maxAge: Int?, perRole: Int = 20) throws -> SquadPlan {
        if departures.isEmpty { throw EngineError.message("Add at least one departing player") }
        if departures.count > 8 { throw EngineError.message("Plan at most 8 departures at once") }
        let excluded = Set(departures)
        var candIds: [Int] = []
        var candIndex: [Int: Int] = [:]
        var edges: [Edge] = []
        var positionsOf: [Int] = []
        var rolesOf: [RoleDef] = []
        for (d, p) in departures.enumerated() {
            let recent = seasonsOf(p).filter { seasons[$0].season >= currentSeason - 2 && minutes($0) >= minMinutes }
            if recent.isEmpty {
                throw EngineError.message("\(players[p].name) has no season of \(Int(minMinutes))+ minutes in the last three, so there is no current level to replace")
            }
            guard let pos = mainPosition(recent) else { throw EngineError.message("\(players[p].name) has no known position") }
            let role = bestRole(recent, pos)
            let target = projection(p, pos)?.z ?? shrunk(recent, pos, regressionMinutes)
            positionsOf.append(pos)
            rolesOf.append(role)
            let w = weights(pos, role)
            var ids: [Int] = [], dists: [Double] = []
            for c in players.indices where !excluded.contains(c) {
                let cr = seasonsOf(c).filter { seasons[$0].season >= currentSeason - 1 && minutes($0) >= minMinutes }
                guard let latest = cr.last else { continue }
                let cp = positionOf(latest)
                if cp < 0 || !positions[pos].neighbours.contains(cp) { continue }
                let age = self.age(c, atSeason: currentSeason + 1)
                if let maxAge, age < 0 || age > maxAge { continue }
                let prof = projection(c, pos)?.z ?? profile(cr, pos)
                let dist = distance(target, prof, w, role).0
                if !dist.isNaN { ids.append(c); dists.append(dist) }
            }
            let top = dists.indices.sorted { dists[$0] < dists[$1] || (dists[$0] == dists[$1] && $0 < $1) }.prefix(perRole)
            for i in top {
                let j: Int
                if let known = candIndex[ids[i]] { j = known } else { candIds.append(ids[i]); j = candIds.count - 1; candIndex[ids[i]] = j }
                edges.append(Edge(left: d, right: j, score: javaRound(1000 * exp(-dists[i] * dists[i] / 4)) / 10.0))
            }
        }
        let left = departures.count
        let best = Matcher.bottleneck(left, candIds.count, edges)
            ?? Matcher.tiered(left, candIds.count, edges, [80, 60, 40, 20, 0])
        let greedy = Matcher.greedy(left, candIds.count, edges)
        func assignments(_ m: Matching) -> [Assignment] {
            (0..<left).map { d in
                let j = m.rightOf[d]
                guard j >= 0 else {
                    return Assignment(departure: departures[d], position: positionsOf[d], role: rolesOf[d], candidate: nil,
                                      age: -1, candidatePosition: nil, club: nil, league: nil, similarity: .nan,
                                      positionNote: nil, leagueNote: nil)
                }
                let c = candIds[j]
                let latest = seasonRange[c].last!
                let cp = positionOf(latest)
                let score = edges.first { $0.left == d && $0.right == j }?.score ?? .nan
                return Assignment(departure: departures[d], position: positionsOf[d], role: rolesOf[d], candidate: c,
                                  age: age(c, atSeason: currentSeason + 1), candidatePosition: cp >= 0 ? cp : nil,
                                  club: clubs[seasons[latest].club], league: seasons[latest].league, similarity: score,
                                  positionNote: positionNote(own: cp >= 0 ? cp : nil, comparedAs: positionsOf[d]),
                                  leagueNote: leagueNote(seasons[latest].league))
            }
        }
        return SquadPlan(matching: assignments(best), weakest: best.size == 0 ? .nan : best.bottleneck,
                         greedy: assignments(greedy), greedyWeakest: greedy.size == 0 ? .nan : greedy.bottleneck,
                         unfilled: (0..<left).filter { best.rightOf[$0] < 0 }.map { departures[$0] },
                         candidates: candIds.count, edges: edges.count, nextSeason: label(currentSeason + 1),
                         perRole: perRole)
    }
}

// MARK: - Compare

struct CompareValue: Sendable { let z: Double; let per90: Double; let percentile: Double; let season: String }

struct CompareColumn: Sendable, Identifiable {
    let player: Int; let position: Int?; let club: String; let league: Int; let age: Int; let seasons: [Int]
    let stats: [Int: CompareValue]; let profile: [Double]; let positionNote: String?; let leagueNote: String?
    var id: Int { player }
}

struct CompareResult: Sendable {
    let position: Int; let role: RoleDef; let statsShown: [Int]; let columns: [CompareColumn]; let similarity: [[Double]]
}

extension Engine {
    func compare(_ ids: [Int]) throws -> CompareResult {
        if ids.count < 2 || ids.count > 3 { throw EngineError.message("Compare 2 or 3 players") }
        if Set(ids).count != ids.count { throw EngineError.message("The same player is listed twice") }
        var recent: [[Int]] = []
        for p in ids {
            let el = seasonsOf(p).filter { minutes($0) >= minMinutes }
            if el.isEmpty { throw EngineError.message("\(players[p].name) has no season with \(Int(minMinutes))+ minutes to compare") }
            recent.append(Array(el.suffix(2)))
        }
        guard let pos = mainPosition(recent[0]) else { throw EngineError.message("\(players[ids[0]].name) has no known position") }
        let role = bestRole(recent[0], pos)
        let w = weights(pos, role)
        let shown = (0..<statCount).filter { !w[$0].isNaN }
        var cols: [CompareColumn] = []
        for (i, p) in ids.enumerated() {
            let ss = recent[i]
            let z = profile(ss, pos)
            var values: [Int: CompareValue] = [:]
            for st in shown {
                for s in ss.reversed() {
                    let v = per90(s, st)
                    if !v.isNaN, let sc = cohort(pos, seasons[s].season)?.scales[st] {
                        values[st] = CompareValue(z: round3(z[st]), per90: round3(v), percentile: round3(percentile(sc, value(s, st))),
                                                  season: label(seasons[s].season))
                        break
                    }
                }
            }
            let latest = ss.last!
            let own = positionOf(latest)
            cols.append(CompareColumn(player: p, position: own >= 0 ? own : nil, club: clubs[seasons[latest].club],
                                      league: seasons[latest].league, age: age(p, atSeason: currentSeason), seasons: ss.map { seasons[$0].season },
                                      stats: values, profile: z, positionNote: positionNote(own: own >= 0 ? own : nil, comparedAs: pos),
                                      leagueNote: leagueNote(seasons[latest].league)))
        }
        var sim = [[Double]](repeating: [Double](repeating: .nan, count: ids.count), count: ids.count)
        for a in ids.indices {
            for b in ids.indices {
                let d = a == b ? 0 : distance(cols[a].profile, cols[b].profile, w, role).0
                sim[a][b] = d.isNaN ? .nan : javaRound(1000 * exp(-d * d / 4)) / 10.0
            }
        }
        return CompareResult(position: pos, role: role, statsShown: shown, columns: cols, similarity: sim)
    }
}

// MARK: - Search

struct SearchHit: Sendable, Identifiable {
    let player: Int; let matchedAlias: String?; let club: String?; let lastSeason: String?
    var id: Int { player }
}

enum Names {
    /** Java Names.normalize: lower case, special letters, accents removed, only a–z and 0–9 kept, single spaces. */
    static func normalize(_ s: String) -> String {
        var t = s.lowercased()
        for (a, b) in [("ß", "ss"), ("ø", "o"), ("æ", "ae"), ("œ", "oe"), ("ł", "l"), ("đ", "d"), ("ð", "d"), ("þ", "th"), ("ı", "i")] {
            t = t.replacingOccurrences(of: a, with: b)
        }
        let scalars = t.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            switch $0.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: return false
            default: return true
            }
        }
        var out = ""
        var pendingSpace = false
        for u in scalars {
            let isAlnum = (u.value >= 97 && u.value <= 122) || (u.value >= 48 && u.value <= 57)
            if isAlnum {
                if pendingSpace && !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.unicodeScalars.append(u)
            } else {
                pendingSpace = true
            }
        }
        return out
    }

    static func tokens(_ s: String) -> [String] {
        let n = normalize(s)
        return n.isEmpty ? [] : n.split(separator: " ").map(String.init)
    }
}

extension Engine {
    func search(_ query: String, limit: Int = 20) -> [SearchHit] {
        let words = Names.tokens(query)
        if words.isEmpty { return [] }
        var hits: [Int]? = nil
        for w in words {
            let ids = playersWithPrefix(w)
            hits = hits.map { h in let set = Set(ids); return h.filter { set.contains($0) } } ?? ids
            if hits!.isEmpty { break }
        }
        let matched = hits ?? []
        let normQuery = words.joined(separator: " ")
        let keys = matched.map { -(careerMinutes[$0] + exactness($0, words, normQuery)) }
        let top = keys.indices.sorted { keys[$0] < keys[$1] || (keys[$0] == keys[$1] && $0 < $1) }.prefix(limit)
        return top.map { i in
            let p = matched[i]
            let last = seasonRange[p].last
            return SearchHit(player: p, matchedAlias: aliasMatched(p, words), club: last.map { clubs[seasons[$0].club] },
                             lastSeason: last.map { label(seasons[$0].season) })
        }
    }

    private func exactness(_ p: Int, _ words: [String], _ normQuery: String) -> Double {
        var best = 0.0
        for tokens in players[p].tokens {
            var score = tokens.joined(separator: " ") == normQuery ? 1e7 : 0
            for w in words where tokens.contains(w) { score += 1e6 }
            best = Swift.max(best, score)
        }
        return best
    }

    private func aliasMatched(_ p: Int, _ words: [String]) -> String? {
        let t = players[p].tokens
        func all(_ k: Int) -> Bool { words.allSatisfy { w in t[k].contains { $0.hasPrefix(w) } } }
        if all(0) { return nil }
        for k in 1..<Swift.max(1, t.count) where all(k) { return players[p].aliases[k - 1] }
        return players[p].aliases.first
    }
}
