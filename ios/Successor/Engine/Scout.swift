import Foundation

// Port of the Java Scout: role fits, per-season analysis, peak detection, profiles and the successor search.

struct RoleFit: Sendable, Identifiable {
    let roleId: String; let name: String; let fit: Double; let coverage: Double
    var id: String { roleId }
}

struct StatLine: Sendable, Identifiable {
    let stat: Int; let key: String; let label: String; let per90: Double; let percentile: Double; let z: Double
    let cohortMedian: Double; let cohortSize: Int
    var id: Int { stat }
}

struct SeasonAnalysis: Sendable, Identifiable {
    let row: Int; let season: Int; let label: String; let club: String; let league: Int; let position: Int?
    let minutes: Int; let eligible: Bool; let roles: [RoleFit]; let stats: [StatLine]
    var id: Int { row }
}

struct Peak: Sendable { let seasons: [Int]; let rating: Double; let automatic: Bool }

struct SuccessorRequest: Sendable {
    var player: Int
    var peakFrom: Int? = nil
    var peakTo: Int? = nil
    var maxAge: Int? = nil
    var leagues: [Int]? = nil
    var minMinutes: Int? = nil
    var k: Int = 10
    var useProjection: Bool = true
    var samePositionOnly: Bool = false
}

struct Candidate: Sendable, Identifiable {
    let player: Int; let ageNextSeason: Int; let position: Int; let club: String; let league: Int
    let seasonsUsed: [Int]; let minutes: Int; let similarity: Double; let distance: Double; let statsCompared: Int
    let closest: [String]; let differences: [String]; let profile: [Double]
    let samePosition: Bool; let positionNote: String?; let leagueNote: String?
    var id: Int { player }
}

struct SuccessorResult: Sendable {
    let target: Int; let position: Int; let role: RoleDef; let peak: Peak; let profile: [Double]
    let weights: [Double]; let candidates: [Candidate]; let pool: Int; let skipped: Int; let nextSeason: String
}

struct ProjectedStat: Sendable, Identifiable {
    let stat: Int; let key: String; let label: String; let lastPer90: Double; let lastSeason: String?
    let per90: Double; let low: Double; let high: Double; let z: Double
    var id: Int { stat }
}

struct ProjectionView: Sendable {
    let player: Int; let position: Int; let season: String; let age: Int; let basedOn: [Int]; let comparables: Int
    let stats: [ProjectedStat]
}

extension Engine {
    static let otherStatWeight = 0.35

    // MARK: analysis

    func fit(_ s: Int, _ role: RoleDef, _ c: Cohort?) -> RoleFit {
        var sum = 0.0, wSum = 0.0, wAll = 0.0
        for (st, w0) in role.weights {
            let w = abs(w0)
            wAll += w
            let v = value(s, st)
            guard let sc = c?.scales[st], !v.isNaN else { continue }
            let pct = percentile(sc, v)
            sum += w * (w0 < 0 ? 100 - pct : pct)
            wSum += w
        }
        let coverage = wAll == 0 ? 0 : wSum / wAll
        return RoleFit(roleId: role.id, name: role.name, fit: coverage < 1.0 / 3 ? .nan : sum / wSum, coverage: coverage)
    }

    /** Every season of a player: role fits and percentiles against his position-season cohort. */
    func analyse(_ player: Int) -> [SeasonAnalysis] {
        seasonsOf(player).map { s in
            let r = seasons[s]
            let pos = positionOf(s)
            let eligible = pos >= 0 && minutes(s) >= minMinutes
            var fits: [RoleFit] = []
            var lines: [StatLine] = []
            if eligible {
                let c = cohort(pos, r.season)
                fits = rolesOf(pos).map { fit(s, $0, c) }
                    .stableSorted { ($0.fit.isNaN ? -1 : $0.fit) > ($1.fit.isNaN ? -1 : $1.fit) }
                for (st, _) in positions[pos].stats {
                    let v = per90(s, st)
                    let adj = value(s, st)
                    if let sc = c?.scales[st], !v.isNaN {
                        lines.append(StatLine(stat: st, key: stats[st].key, label: stats[st].label, per90: v,
                                              percentile: percentile(sc, adj), z: z(sc, adj), cohortMedian: sc.median,
                                              cohortSize: sc.count))
                    }
                }
            }
            return SeasonAnalysis(row: s, season: r.season, label: label(r.season), club: clubs[r.club], league: r.league,
                                  position: pos >= 0 ? pos : nil, minutes: Int(minutes(s)), eligible: eligible,
                                  roles: fits, stats: lines)
        }
    }

    // MARK: the building blocks

    /** Position with the most minutes over the seasons (the first on ties, as Java's EnumMap + max). */
    func mainPosition(_ ss: [Int]) -> Int? {
        var mins = [Double?](repeating: nil, count: positions.count)
        for s in ss {
            let x = positionOf(s)
            if x >= 0 { mins[x] = (mins[x] ?? 0) + minutes(s) }
        }
        var best: Int? = nil
        for p in mins.indices { if let m = mins[p], best == nil || m > mins[best!]! { best = p } }
        return best
    }

    func bestRole(_ ss: [Int], _ pos: Int) -> RoleDef {
        var best: RoleDef? = nil
        var bestFit = -1.0
        for r in rolesOf(pos) {
            var sum = 0.0, n = 0
            for s in ss {
                let f = fit(s, r, cohort(pos, seasons[s].season)).fit
                if !f.isNaN { sum += f; n += 1 }
            }
            if n > 0 && sum / Double(n) > bestFit { bestFit = sum / Double(n); best = r }
        }
        if best == nil {
            var bestCoverage = -1.0
            for r in rolesOf(pos) {
                var cov = 0.0
                for s in ss { cov += fit(s, r, cohort(pos, seasons[s].season)).coverage }
                if cov > bestCoverage { bestCoverage = cov; best = r }
            }
        }
        return best!
    }

    func peak(_ eligible: [Int], _ pos: Int, _ role: RoleDef, from: Int?, to: Int?) throws -> Peak {
        var order: [Int] = []
        var fitBy: [Int: Double] = [:]
        var minutesBy: [Int: Double] = [:]
        for s in eligible {
            let y = seasons[s].season
            let f = fit(s, role, cohort(pos, y)).fit
            if !f.isNaN {
                if fitBy[y] == nil || minutes(s) > minutesBy[y]! {
                    if fitBy[y] == nil { order.append(y) }
                    fitBy[y] = f
                    minutesBy[y] = minutes(s)
                }
            }
        }
        if from != nil || to != nil {
            let a = from ?? Int.min, b = to ?? Int.max
            if a > b { throw EngineError.message("The peak's first season must not be after its last") }
            let chosen = order.filter { $0 >= a && $0 <= b }.sorted()
            if chosen.isEmpty { throw EngineError.message("No eligible season in that range") }
            let r = chosen.map { fitBy[$0]! }.reduce(0, +) / Double(chosen.count)
            return Peak(seasons: chosen, rating: round1(r), automatic: false)
        }
        let years = order.sorted()
        if years.isEmpty { throw EngineError.message("Not enough measured stats to rate any season for this role") }
        let w = min(3, years.count)
        var best = -1.0, bestStart = 0, bestLen = 1
        var len = w
        while len >= 1 && best < 0 {
            var i = 0
            while i + len <= years.count {
                if years[i + len - 1] - years[i] == len - 1 {
                    var sum = 0.0
                    for j in i..<(i + len) { sum += fitBy[years[j]]! }
                    let mean = sum / Double(len)
                    if mean > best || (mean == best && years[i] > years[bestStart]) {
                        best = mean; bestStart = i; bestLen = len
                    }
                }
                i += 1
            }
            len -= 1
        }
        return Peak(seasons: Array(years[bestStart..<(bestStart + bestLen)]), rating: round1(best), automatic: true)
    }

    /** Minutes-weighted mean robust z per stat over the seasons, each scaled in (pos, its season). */
    func profile(_ ss: [Int], _ pos: Int) -> [Double] {
        var sum = [Double](repeating: 0, count: statCount)
        var w = [Double](repeating: 0, count: statCount)
        for s in ss {
            let c = cohort(pos, seasons[s].season)
            for (st, _) in positions[pos].stats {
                let v = value(s, st)
                if let sc = c?.scales[st], !v.isNaN {
                    sum[st] += minutes(s) * z(sc, v)
                    w[st] += minutes(s)
                }
            }
        }
        return (0..<statCount).map { w[$0] > 0 ? sum[$0] / w[$0] : .nan }
    }

    /** The peak shrunk toward the median like the projections: z × n / (n + R), n = minutes behind it. */
    func shrunk(_ ss: [Int], _ pos: Int, _ r: Double) -> [Double] {
        var z = profile(ss, pos)
        var n = [Double](repeating: 0, count: statCount)
        for s in ss {
            let v = zVector(s, pos)
            for i in 0..<statCount where !v[i].isNaN { n[i] += minutes(s) }
        }
        for i in 0..<statCount where !z[i].isNaN { z[i] *= n[i] / (n[i] + r) }
        return z
    }

    /** Java's EnumMap of weights as a dense array in stat order (NaN = not used). */
    func weights(_ pos: Int, _ role: RoleDef) -> [Double] {
        var w = [Double](repeating: .nan, count: statCount)
        for (st, x) in positions[pos].stats { w[st] = Engine.otherStatWeight * x }
        for (st, x) in role.weights { w[st] = Swift.max(0.5, abs(x)) }
        return w
    }

    /** {distance, stats compared}; NaN when the overlap is too thin to judge. */
    func distance(_ a: [Double], _ b: [Double], _ weights: [Double], _ role: RoleDef) -> (Double, Int) {
        var num = 0.0, den = 0.0, n = 0, roleW = 0.0, roleCovered = 0.0
        for i in 0..<statCount where !weights[i].isNaN {
            let w = weights[i]
            let inRole = role.statSet.contains(i)
            if inRole && !a[i].isNaN { roleW += w }
            if a[i].isNaN || b[i].isNaN { continue }
            let diff = clamp4(a[i]) - clamp4(b[i])
            num += w * diff * diff
            den += w
            n += 1
            if inRole { roleCovered += w }
        }
        if n < 3 || roleW == 0 || roleCovered / roleW < 0.4 { return (.nan, n) }
        return ((num / den).squareRoot(), n)
    }

    func explain(_ t: [Double], _ c: [Double], _ weights: [Double], closest: Bool) -> [String] {
        let shared = (0..<statCount).filter { !weights[$0].isNaN && !t[$0].isNaN && !c[$0].isNaN }
        let gap: (Int) -> Double = { weights[$0] * abs(t[$0] - c[$0]) }
        let sorted = closest
            ? shared.stableSorted { gap($0) < gap($1) || (gap($0) == gap($1) && -weights[$0] < -weights[$1]) }
            : shared.stableSorted { gap($0) > gap($1) }
        var out: [String] = []
        for s in sorted.prefix(3) {
            let g = c[s] - t[s]
            if closest {
                out.append(stats[s].label)
            } else if abs(g) >= 0.5 {
                out.append((g > 0 ? "more " : "fewer ") + Engine.phrase(stats[s].label) + String(format: " (%+.1f SD)", g))
            }
        }
        return out
    }

    static func phrase(_ label: String) -> String {
        let chars = Array(label)
        if chars.count > 1 && chars[1].isUppercase { return label }
        return chars.first.map { String($0).lowercased() + String(chars.dropFirst()) } ?? label
    }

    func positionNote(own: Int?, comparedAs: Int) -> String? {
        guard let own, own != comparedAs else { return nil }
        let noun = positions[comparedAs].label.lowercased()
        let article = "aeiou".contains(noun.first ?? "x") ? "an " : "a "
        return positions[own].label + ": a different position, compared as " + article + noun
    }

    func leagueNote(_ league: Int) -> String? {
        let l = leagues[league]
        if l.big5 { return nil }
        return l.label + " (" + l.country + "): outside the Big 5; "
            + (l.headline.map { "converted to Big-5 level by measured factors (\($0))" }
               ?? "stats not adjusted for league strength")
    }

    // MARK: successor search

    func successor(_ q: SuccessorRequest) throws -> SuccessorResult {
        let target = q.player
        let minMin = Double(q.minMinutes ?? Int(minMinutes))
        let eligible = seasonsOf(target).filter { minutes($0) >= minMinutes }
        if eligible.isEmpty {
            throw EngineError.message("\(players[target].name) has no season with at least \(Int(minMinutes)) minutes, so there is no profile to match")
        }
        guard let pos = mainPosition(eligible) else { throw EngineError.message("\(players[target].name) has no known position") }
        let role = bestRole(eligible, pos)
        let pk = try peak(eligible, pos, role, from: q.peakFrom, to: q.peakTo)
        let peakSeasons = eligible.filter { pk.seasons.contains(seasons[$0].season) }
        let targetProfile = q.useProjection ? shrunk(peakSeasons, pos, regressionMinutes) : profile(peakSeasons, pos)
        let w = weights(pos, role)
        let allowed: [Int] = q.samePositionOnly ? [pos] : positions[pos].neighbours
        let leagueSet = Set(q.leagues ?? Array(leagues.indices))

        var ids: [Int] = [], profiles: [[Double]] = [], used: [[Int]] = [], dists: [Double] = [], compared: [Int] = []
        var skipped = 0
        for p in players.indices where p != target {
            let recent = seasonsOf(p).filter { seasons[$0].season >= currentSeason - 1 && minutes($0) >= minMin }
            guard let latest = recent.last else { continue }
            let cp = positionOf(latest)
            if cp < 0 || !allowed.contains(cp) || !leagueSet.contains(seasons[latest].league) { continue }
            let age = self.age(p, atSeason: currentSeason + 1)
            if let maxAge = q.maxAge, age < 0 || age > maxAge { continue }
            var prof: [Double]? = q.useProjection ? projection(p, pos)?.z : nil
            if prof == nil { prof = profile(recent, pos) }
            let d = distance(targetProfile, prof!, w, role)
            if d.0.isNaN { skipped += 1; continue }
            ids.append(p); profiles.append(prof!); used.append(recent); dists.append(d.0); compared.append(d.1)
        }
        // top-k: smallest distance first, ties by pool order (as Java's quickselect + sort)
        let top = dists.indices.sorted { dists[$0] < dists[$1] || (dists[$0] == dists[$1] && $0 < $1) }.prefix(q.k)
        let candidates = top.map { i -> Candidate in
            let p = ids[i]
            let latest = used[i].last!
            let own = positionOf(latest)
            let d = dists[i]
            return Candidate(player: p, ageNextSeason: age(p, atSeason: currentSeason + 1), position: own,
                             club: clubs[seasons[latest].club], league: seasons[latest].league,
                             seasonsUsed: used[i].map { seasons[$0].season },
                             minutes: Int(used[i].map(minutes).reduce(0, +)),
                             similarity: round1(100 * exp(-d * d / 4)), distance: round3(d), statsCompared: compared[i],
                             closest: explain(targetProfile, profiles[i], w, closest: true),
                             differences: explain(targetProfile, profiles[i], w, closest: false),
                             profile: profiles[i], samePosition: own == pos,
                             positionNote: positionNote(own: own >= 0 ? own : nil, comparedAs: pos),
                             leagueNote: leagueNote(seasons[latest].league))
        }
        return SuccessorResult(target: target, position: pos, role: role, peak: pk, profile: targetProfile, weights: w,
                               candidates: candidates, pool: dists.count, skipped: skipped,
                               nextSeason: label(currentSeason + 1))
    }

    // MARK: projection view (player page)

    func projectionView(_ player: Int) throws -> ProjectionView {
        let eligible = seasonsOf(player).filter { minutes($0) >= projectionMinMinutes }
        if eligible.isEmpty {
            throw EngineError.message("\(players[player].name) has no season with \(Int(projectionMinMinutes))+ minutes to project from")
        }
        guard let pos = mainPosition(Array(eligible.suffix(3))) else {
            throw EngineError.message("\(players[player].name) has no known position")
        }
        guard let pr = projection(player, pos) else {
            throw EngineError.message("\(players[player].name) played under \(Int(projectionMinMinutes)) minutes in each of the last three seasons, so there is nothing recent to project from")
        }
        var out: [ProjectedStat] = []
        for (st, _) in positions[pos].stats where !pr.z[st].isNaN {
            var sc: StatScale? = nil
            var y = currentSeason
            while y >= currentSeason - 3 && sc == nil { sc = cohort(pos, y)?.scales[st]; y -= 1 }
            guard let sc else { continue }
            var last = Double.nan
            var lastLabel: String? = nil
            for s in eligible { let v = per90(s, st); if !v.isNaN { last = v; lastLabel = label(seasons[s].season) } }
            let p90 = Swift.max(0, sc.median + pr.z[st] * sc.scale)
            let low = pr.low[st].isNaN ? Double.nan : Swift.max(0, sc.median + pr.low[st] * sc.scale)
            let high = pr.high[st].isNaN ? Double.nan : Swift.max(0, sc.median + pr.high[st] * sc.scale)
            out.append(ProjectedStat(stat: st, key: stats[st].key, label: stats[st].label, lastPer90: round3(last),
                                     lastSeason: lastLabel, per90: round3(p90), low: round3(low), high: round3(high),
                                     z: round3(pr.z[st])))
        }
        return ProjectionView(player: player, position: pos, season: label(currentSeason + 1), age: pr.age,
                              basedOn: pr.basedOn, comparables: pr.comparables, stats: out)
    }
}
