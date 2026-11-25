import Foundation

// The phone side of Successor. The Java engine on the Mac does the heavy statistics (linking sources, cohorts,
// league strength, projections) and exports them (PhoneExport); this engine loads that export and answers the same
// queries as the Java Scout, SquadPlanner, Comparer and PlayerSearch, rule for rule, so results match the Mac.

// MARK: - Export format (meta.json)

struct MetaFile: Decodable {
    let format: Int
    let origin: String?
    let currentSeason: Int
    let minMinutes: Int
    let projectionMinMinutes: Int
    let regressionMinutes: Double
    let rangeScale: Double
    let method: String
    let stats: [StatInfo]
    let positions: [PositionInfo]
    let leagues: [LeagueInfo]
    let roles: [RoleInfo]
    let players: PlayersBlock
    let seasons: SeasonsBlock
    let sources: [String]
    let clubs: [String]
    let cohorts: [CohortInfo]
    let projections: [ProjectionInfo]
    let backtest: BacktestInfo

    struct StatInfo: Decodable { let key: String; let label: String; let per90: Bool; let group: String }
    struct PositionInfo: Decodable { let code: String; let label: String; let neighbours: [Int]; let stats: [[Double]] }
    struct LeagueInfo: Decodable {
        let code: String; let label: String; let country: String; let big5: Bool; let headline: String?
        let factors: [Double]
    }
    struct RoleInfo: Decodable {
        let id: String; let name: String; let position: Int; let description: String; let weights: [[Double]]
    }
    struct PlayersBlock: Decodable {
        let id: [String]; let name: [String]; let birthDate: [String]; let birthYear: [Int]; let nation: [String]
        let position: [Int]; let subPosition: [String]; let foot: [String]; let height: [Int]; let value: [Double]
        let aliases: [[String]]; let tokens: [[[String]]]
    }
    struct SeasonsBlock: Decodable {
        let player: [Int]; let season: [Int]; let league: [Int]; let club: [Int]; let position: [Int]; let sources: [Int]
    }
    struct CohortInfo: Decodable { let pos: Int; let season: Int; let players: Int; let stats: [[Double]] }
    struct ProjectionInfo: Decodable {
        let player: Int; let pos: Int; let age: Int; let comparables: Int; let basedOn: [Int]; let offset: Int
    }
    struct BacktestInfo: Decodable, Sendable {
        let testSeasons: [Int]; let trainSeasons: [Int]; let chosenMethod: String; let overall: [MethodScore]
        let leagueCheck: LeagueCheck?
        struct MethodScore: Decodable, Sendable { let method: String; let mae: Double?; let vsNaive: Double?; let coverage80: Double? }
        struct LeagueCheck: Decodable, Sendable {
            let arrivals: Int; let arrivalsMae: Double?; let arrivalsMaeUnadjusted: Double?; let overallMae: Double?
            let overallMaeUnadjusted: Double?; let factorsFrom: String
        }
    }
}

// MARK: - Engine data

struct StatDef: Sendable { let key: String; let label: String; let per90: Bool; let group: String }
struct PositionDef: Sendable {
    let code: String; let label: String; let neighbours: [Int]
    /** Every stat a role at this position uses, with its largest weight, in Java's insertion order. */
    let stats: [(stat: Int, weight: Double)]
}
struct LeagueDef: Sendable {
    let code: String; let label: String; let country: String; let big5: Bool; let headline: String?; let factors: [Double]
}
struct RoleDef: Sendable {
    let id: String; let name: String; let position: Int; let description: String
    let weights: [(stat: Int, weight: Double)]
    let statSet: Set<Int>
}
struct PlayerRow: Sendable {
    let id: String; let name: String; let aliases: [String]; let birth: (y: Int, m: Int, d: Int)?; let birthYear: Int
    let nation: String; let position: Int; let subPosition: String; let foot: String; let height: Int; let value: Double
    let tokens: [[String]]
}
struct SeasonRow: Sendable {
    let player: Int; let season: Int; let league: Int; let club: Int; let position: Int; let sources: Int
}
struct StatScale: Sendable { let median: Double; let q1: Double; let q3: Double; let scale: Double; let offset: Int; let count: Int }
struct Cohort: Sendable { let players: Int; let scales: [StatScale?] }
struct Projection: Sendable {
    let age: Int; let comparables: Int; let basedOn: [Int]; let z: [Double]; let low: [Double]; let high: [Double]
}

enum EngineError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

final class Engine: @unchecked Sendable {
    let origin: String
    let isSample: Bool
    let currentSeason: Int
    let minMinutes: Double
    let projectionMinMinutes: Double
    let regressionMinutes: Double
    let method: String
    let stats: [StatDef]
    let positions: [PositionDef]
    let leagues: [LeagueDef]
    let roles: [RoleDef]
    let players: [PlayerRow]
    let seasons: [SeasonRow]
    let clubs: [String]
    let sourceNames: [String]
    let backtest: MetaFile.BacktestInfo
    /** Season stats, dense: stats of season i at [i * statCount + stat], NaN when not measured. */
    private let values: [Double]
    let statCount: Int
    /** Each player's seasons, in Java's order (contiguous in the export). */
    let seasonRange: [Range<Int>]
    private let cohorts: [Int: Cohort]
    private let cohortValues: [Double]
    private let projections: [Int: Projection]
    let playerIndex: [String: Int]
    let careerMinutes: [Double]
    private let searchIndex: [(token: String, player: Int)]

    let MINUTES: Int
    let CLEAN_SHEETS: Int

    // MARK: loading

    static func load(from dir: URL, sample: Bool) throws -> Engine {
        let meta = try JSONDecoder().decode(MetaFile.self, from: Data(contentsOf: dir.appendingPathComponent("meta.json")))
        guard meta.format == 1 else { throw EngineError.message("unsupported data format \(meta.format)") }
        let seasonsBin = try Data(contentsOf: dir.appendingPathComponent("seasons.bin"), options: .alwaysMapped)
        let cohortsBin = try Data(contentsOf: dir.appendingPathComponent("cohorts.bin"), options: .alwaysMapped)
        let projBin = try Data(contentsOf: dir.appendingPathComponent("projections.bin"), options: .alwaysMapped)
        return Engine(meta: meta, seasonsBin: seasonsBin, cohortsBin: cohortsBin, projBin: projBin, sample: sample)
    }

    private init(meta: MetaFile, seasonsBin: Data, cohortsBin: Data, projBin: Data, sample: Bool) {
        origin = meta.origin ?? ""
        isSample = sample
        currentSeason = meta.currentSeason
        minMinutes = Double(meta.minMinutes)
        projectionMinMinutes = Double(meta.projectionMinMinutes)
        regressionMinutes = meta.regressionMinutes
        method = meta.method
        let statDefs = meta.stats.map { StatDef(key: $0.key, label: $0.label, per90: $0.per90, group: $0.group) }
        stats = statDefs
        statCount = statDefs.count
        let posDefs = meta.positions.map { p in
            PositionDef(code: p.code, label: p.label, neighbours: p.neighbours,
                        stats: p.stats.map { (Int($0[0]), $0[1]) })
        }
        positions = posDefs
        let minutesStat = statDefs.firstIndex { $0.key == "minutes" } ?? 0
        leagues = meta.leagues.map {
            LeagueDef(code: $0.code, label: $0.label, country: $0.country, big5: $0.big5, headline: $0.headline,
                      factors: $0.factors)
        }
        roles = meta.roles.map { r in
            let w = r.weights.map { (stat: Int($0[0]), weight: $0[1]) }
            return RoleDef(id: r.id, name: r.name, position: r.position, description: r.description, weights: w,
                           statSet: Set(w.map(\.stat)))
        }
        let pb = meta.players
        var ps: [PlayerRow] = []
        ps.reserveCapacity(pb.id.count)
        for i in pb.id.indices {
            var birth: (Int, Int, Int)? = nil
            let b = pb.birthDate[i].split(separator: "-").compactMap { Int($0) }
            if b.count == 3 { birth = (b[0], b[1], b[2]) }
            ps.append(PlayerRow(id: pb.id[i], name: pb.name[i], aliases: pb.aliases[i], birth: birth,
                                birthYear: pb.birthYear[i], nation: pb.nation[i], position: pb.position[i],
                                subPosition: pb.subPosition[i], foot: pb.foot[i], height: pb.height[i],
                                value: pb.value[i], tokens: pb.tokens[i]))
        }
        players = ps
        var index: [String: Int] = [:]
        for (i, p) in ps.enumerated() { index[p.id] = i }
        playerIndex = index
        clubs = meta.clubs
        sourceNames = meta.sources
        backtest = meta.backtest

        let sb = meta.seasons
        var rows: [SeasonRow] = []
        rows.reserveCapacity(sb.player.count)
        for i in sb.player.indices {
            rows.append(SeasonRow(player: sb.player[i], season: sb.season[i], league: sb.league[i], club: sb.club[i],
                                  position: sb.position[i], sources: sb.sources[i]))
        }
        seasons = rows
        let n = statDefs.count
        var vals = [Double](repeating: .nan, count: rows.count * n)
        seasonsBin.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var at = 0
            for i in 0..<rows.count {
                let mask = raw.loadUnaligned(fromByteOffset: at, as: UInt64.self).littleEndian
                at += 8
                for st in 0..<n where mask & (UInt64(1) << UInt64(st)) != 0 {
                    vals[i * n + st] = Double(bitPattern: raw.loadUnaligned(fromByteOffset: at, as: UInt64.self).littleEndian)
                    at += 8
                }
            }
        }
        values = vals
        var ranges = [Range<Int>](repeating: 0..<0, count: ps.count)
        var start = 0
        while start < rows.count {
            var end = start
            while end < rows.count && rows[end].player == rows[start].player { end += 1 }
            ranges[rows[start].player] = start..<end
            start = end
        }
        seasonRange = ranges
        MINUTES = minutesStat
        CLEAN_SHEETS = statDefs.firstIndex { $0.key == "clean_sheets" } ?? -1

        cohortValues = cohortsBin.withUnsafeBytes { raw in
            (0..<(raw.count / 8)).map { Double(bitPattern: raw.loadUnaligned(fromByteOffset: $0 * 8, as: UInt64.self).littleEndian) }
        }
        var cs: [Int: Cohort] = [:]
        for c in meta.cohorts {
            var scales = [StatScale?](repeating: nil, count: n)
            for s in c.stats {
                scales[Int(s[0])] = StatScale(median: s[1], q1: s[2], q3: s[3], scale: s[4], offset: Int(s[5]),
                                              count: Int(s[6]))
            }
            cs[c.pos * 10_000 + c.season] = Cohort(players: c.players, scales: scales)
        }
        cohorts = cs

        let projDoubles: [Double] = projBin.withUnsafeBytes { raw in
            (0..<(raw.count / 8)).map { Double(bitPattern: raw.loadUnaligned(fromByteOffset: $0 * 8, as: UInt64.self).littleEndian) }
        }
        var pr: [Int: Projection] = [:]
        for p in meta.projections {
            var z = [Double](repeating: .nan, count: n), lo = z, hi = z
            var at = p.offset
            for (st, _) in posDefs[p.pos].stats {
                z[st] = projDoubles[at]; lo[st] = projDoubles[at + 1]; hi[st] = projDoubles[at + 2]
                at += 3
            }
            pr[p.player * 16 + p.pos] = Projection(age: p.age, comparables: p.comparables, basedOn: p.basedOn, z: z,
                                                    low: lo, high: hi)
        }
        projections = pr

        var career = [Double](repeating: 0, count: ps.count)
        var tokens: [(String, Int)] = []
        for i in ps.indices {
            for s in ranges[i] { career[i] += Self.minutesOf(vals, s, n, minutesStat) }
            var seen = Set<String>()
            for spelling in ps[i].tokens { for t in spelling where seen.insert(t).inserted { tokens.append((t, i)) } }
        }
        careerMinutes = career
        searchIndex = tokens.sorted { $0.0 < $1.0 || ($0.0 == $1.0 && $0.1 < $1.1) }
    }

    private static func minutesOf(_ vals: [Double], _ s: Int, _ n: Int, _ minutes: Int) -> Double {
        let m = vals[s * n + minutes]
        return m.isNaN ? 0 : m
    }

    // MARK: basics (Java PlayerSeason, Player, Cohorts)

    func stat(_ s: Int, _ st: Int) -> Double { values[s * statCount + st] }

    func minutes(_ s: Int) -> Double {
        let m = stat(s, MINUTES)
        return m.isNaN ? 0 : m
    }

    /** Value per 90 for per-90 stats (raw total otherwise; clean sheets per 90 too); NaN if missing or no minutes. */
    func per90(_ s: Int, _ st: Int) -> Double {
        let v = stat(s, st)
        if st == CLEAN_SHEETS {
            let m = minutes(s)
            return v.isNaN || m <= 0 ? .nan : v * 90 / m
        }
        if v.isNaN || !stats[st].per90 { return v }
        let m = minutes(s)
        return m <= 0 ? .nan : v * 90.0 / m
    }

    /** The value compared with the Big-5 cohort: raw in the Big 5, times the league-strength factor elsewhere. */
    func value(_ s: Int, _ st: Int) -> Double {
        let v = per90(s, st)
        let l = leagues[seasons[s].league]
        return l.big5 || v.isNaN ? v : v * l.factors[st]
    }

    func cohort(_ pos: Int, _ season: Int) -> Cohort? { cohorts[pos * 10_000 + season] }

    func percentile(_ sc: StatScale, _ x: Double) -> Double {
        // count below and equal by binary search over the ascending values (Java counts them in a loop)
        var lo = sc.offset, hi = sc.offset + sc.count
        while lo < hi { let mid = (lo + hi) / 2; if cohortValues[mid] < x { lo = mid + 1 } else { hi = mid } }
        let below = lo - sc.offset
        var lo2 = lo, hi2 = sc.offset + sc.count
        while lo2 < hi2 { let mid = (lo2 + hi2) / 2; if cohortValues[mid] <= x { lo2 = mid + 1 } else { hi2 = mid } }
        let equal = lo2 - lo
        return 100.0 * (Double(below) + 0.5 * Double(equal)) / Double(sc.count)
    }

    func z(_ sc: StatScale, _ x: Double) -> Double { (x - sc.median) / sc.scale }

    /** Robust z-scores of a season against (pos, its season); NaN where missing or not scaled. */
    func zVector(_ s: Int, _ pos: Int) -> [Double] {
        var out = [Double](repeating: .nan, count: statCount)
        let c = cohort(pos, seasons[s].season)
        for (st, _) in positions[pos].stats {
            let v = value(s, st)
            if let sc = c?.scales[st], !v.isNaN { out[st] = z(sc, v) }
        }
        return out
    }

    func projection(_ player: Int, _ pos: Int) -> Projection? { projections[player * 16 + pos] }

    func seasonsOf(_ player: Int) -> [Int] { Array(seasonRange[player]) }

    func positionOf(_ s: Int) -> Int {
        let p = seasons[s].position
        return p >= 0 ? p : players[seasons[s].player].position
    }

    /** Java Player.ageAtSeason: completed years on 1 July of the season's start year. */
    func age(_ player: Int, atSeason y: Int) -> Int {
        let p = players[player]
        if let b = p.birth {
            return y - b.y - ((b.m > 7 || (b.m == 7 && b.d > 1)) ? 1 : 0)
        }
        return p.birthYear > 0 ? y - p.birthYear : -1
    }

    func label(_ season: Int) -> String { String(format: "%d/%02d", season, (season + 1) % 100) }

    func rolesOf(_ pos: Int) -> [RoleDef] { roles.filter { $0.position == pos } }

    func role(id: String) -> RoleDef? { roles.first { $0.id == id } }

    func position(code: String) -> Int? { positions.firstIndex { $0.code == code } }

    func league(label: String) -> Int? { leagues.firstIndex { $0.label == label || $0.code == label } }

    // MARK: search index

    /** Players with a name word starting with {@code prefix} (normalised), ascending, unique. */
    func playersWithPrefix(_ prefix: String) -> [Int] {
        var lo = 0, hi = searchIndex.count
        while lo < hi { let mid = (lo + hi) / 2; if searchIndex[mid].token < prefix { lo = mid + 1 } else { hi = mid } }
        var out = Set<Int>()
        var i = lo
        while i < searchIndex.count && searchIndex[i].token.hasPrefix(prefix) { out.insert(searchIndex[i].player); i += 1 }
        return out.sorted()
    }
}

// MARK: - Java-compatible helpers

/** Java's Math.round(x) as a double: floor(x + 0.5). */
@inline(__always) func javaRound(_ x: Double) -> Double { (x + 0.5).rounded(.down) }
func round1(_ v: Double) -> Double { v.isNaN ? .nan : javaRound(v * 10) / 10.0 }
func round3(_ v: Double) -> Double { v.isNaN ? .nan : javaRound(v * 1000) / 1000.0 }
/** Java's Math.max(-4, Math.min(4, x)), NaN-preserving. */
@inline(__always) func clamp4(_ x: Double) -> Double { x.isNaN ? .nan : Swift.max(-4, Swift.min(4, x)) }

extension Array {
    /** A stable sort (Java's List.sort is stable; Swift's sort is not guaranteed to be). */
    func stableSorted(by less: (Element, Element) -> Bool) -> [Element] {
        enumerated().sorted { a, b in less(a.element, b.element) || (!less(b.element, a.element) && a.offset < b.offset) }
            .map(\.element)
    }
}
