import XCTest
@testable import Successor

/**
 * The phone must give the Mac's answers. golden.json holds the Java engine's results for fixed queries (exported with
 * the data); these tests run the Swift engine on the same data and compare: same players in the same order, same
 * similarities, percentiles, projections and matchings.
 */
final class GoldenTests: XCTestCase {
    nonisolated(unsafe) static var engine: Engine!
    nonisolated(unsafe) static var golden: [String: Any] = [:]

    override class func setUp() {
        super.setUp()
        guard let (dir, sample) = AppModel.dataDirectory() else { return }
        engine = try? Engine.load(from: dir, sample: sample)
        if let data = try? Data(contentsOf: dir.appendingPathComponent("golden.json")),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            golden = obj
        }
    }

    private var e: Engine { Self.engine }

    private func idx(_ id: String) -> Int { e.playerIndex[id]! }

    private func num(_ x: Any?) -> Double { (x as? NSNumber)?.doubleValue ?? .nan }

    private func same(_ a: Double, _ b: Double, _ msg: String, file: StaticString = #filePath, line: UInt = #line) {
        if a.isNaN && b.isNaN { return }
        XCTAssertEqual(a, b, accuracy: 1e-9, msg, file: file, line: line)
    }

    func testDataLoads() {
        XCTAssertNotNil(Self.engine, "the bundled data must load")
        XCTAssertFalse(Self.golden.isEmpty, "golden.json must be bundled with the data")
        XCTAssertGreaterThan(e.players.count, 100)
    }

    func testSearchMatchesJava() {
        for case let g as [String: Any] in Self.golden["search"] as? [Any] ?? [] {
            let q = g["query"] as! String
            let expected = g["hits"] as! [String]
            let got = e.search(q, limit: 8).map { e.players[$0.player].id }
            XCTAssertEqual(got, expected, "search \"\(q)\"")
        }
    }

    func testSuccessorSearchesMatchJava() {
        let all = Self.golden["successor"] as? [[String: Any]] ?? []
        XCTAssertFalse(all.isEmpty)
        for g in all {
            let r = g["request"] as! [String: Any]
            var q = SuccessorRequest(player: idx(r["player"] as! String))
            q.k = (r["k"] as! NSNumber).intValue
            q.maxAge = (r["maxAge"] as? NSNumber)?.intValue
            q.minMinutes = (r["minMinutes"] as? NSNumber)?.intValue
            q.leagues = (r["leagues"] as? [String])?.map { e.league(label: $0)! }
            q.useProjection = r["useProjection"] as! Bool
            q.samePositionOnly = r["samePositionOnly"] as! Bool
            let name = "\(r["player"]!) \(r)"
            let res = try! e.successor(q)
            XCTAssertEqual(e.positions[res.position].code, g["position"] as? String, name)
            XCTAssertEqual(res.role.id, g["role"] as? String, name)
            XCTAssertEqual(res.peak.seasons, (g["peak"] as! [NSNumber]).map(\.intValue), name)
            XCTAssertEqual(res.peak.rating, num(g["rating"]), name)
            XCTAssertEqual(res.pool, (g["pool"] as! NSNumber).intValue, name)
            XCTAssertEqual(res.skipped, (g["skipped"] as! NSNumber).intValue, name)
            let expected = g["candidates"] as! [[String: Any]]
            XCTAssertEqual(res.candidates.map { e.players[$0.player].id }, expected.map { $0["id"] as! String }, name)
            for (c, x) in zip(res.candidates, expected) {
                same(c.similarity, num(x["similarity"]), name)
                same(c.distance, num(x["distance"]), name)
                XCTAssertEqual(c.statsCompared, (x["statsCompared"] as! NSNumber).intValue, name)
                XCTAssertEqual(c.positionNote, x["positionNote"] as? String, name)
                XCTAssertEqual(c.leagueNote, x["leagueNote"] as? String, name)
                XCTAssertEqual(c.closest, x["closest"] as? [String], name)
                XCTAssertEqual(c.differences, x["differences"] as? [String], name)
            }
        }
    }

    func testSeasonAnalysisMatchesJava() {
        for case let g as [String: Any] in Self.golden["analyse"] as? [Any] ?? [] {
            let id = g["player"] as! String
            let got = e.analyse(idx(id))
            let expected = g["seasons"] as! [[String: Any]]
            XCTAssertEqual(got.count, expected.count, id)
            for (a, x) in zip(got, expected) {
                XCTAssertEqual(a.season, (x["season"] as! NSNumber).intValue, id)
                XCTAssertEqual(a.eligible, x["eligible"] as! Bool, id)
                let roles = x["roles"] as! [[Any]]
                XCTAssertEqual(a.roles.map(\.roleId), roles.map { $0[0] as! String }, "\(id) \(a.season)")
                for (f, y) in zip(a.roles, roles) {
                    same(f.fit, num(y[1]), "\(id) \(a.season) \(f.roleId)")
                    same(f.coverage, num(y[2]), "\(id) \(a.season) \(f.roleId)")
                }
                let lines = x["stats"] as! [[Any]]
                XCTAssertEqual(a.stats.map(\.key), lines.map { $0[0] as! String }, "\(id) \(a.season)")
                for (l, y) in zip(a.stats, lines) {
                    same(l.per90, num(y[1]), "\(id) \(a.season) \(l.key)")
                    same(l.percentile, num(y[2]), "\(id) \(a.season) \(l.key)")
                    same(l.z, num(y[3]), "\(id) \(a.season) \(l.key)")
                }
            }
        }
    }

    func testProjectionsMatchJava() {
        for case let g as [String: Any] in Self.golden["projection"] as? [Any] ?? [] {
            let id = g["player"] as! String
            let v = try! e.projectionView(idx(id))
            XCTAssertEqual(e.positions[v.position].code, g["position"] as? String, id)
            let expected = g["stats"] as! [[Any]]
            XCTAssertEqual(v.stats.map(\.key), expected.map { $0[0] as! String }, id)
            for (s, y) in zip(v.stats, expected) {
                same(s.lastPer90, num(y[1]), "\(id) \(s.key) last")
                same(s.per90, num(y[2]), "\(id) \(s.key)")
                same(s.low, num(y[3]), "\(id) \(s.key) low")
                same(s.high, num(y[4]), "\(id) \(s.key) high")
            }
        }
    }

    func testSquadPlanMatchesJava() {
        for case let g as [String: Any] in Self.golden["squad"] as? [Any] ?? [] {
            let deps = (g["departures"] as! [String]).map(idx)
            let plan = try! e.plan(departures: deps, maxAge: (g["maxAge"] as! NSNumber).intValue,
                                   perRole: (g["perRole"] as! NSNumber).intValue)
            same(plan.weakest, num(g["weakest"]), "weakest")
            same(plan.greedyWeakest, num(g["greedyWeakest"]), "greedy weakest")
            for (list, key) in [(plan.matching, "matching"), (plan.greedy, "greedy")] {
                let expected = g[key] as! [[Any]]
                XCTAssertEqual(list.map { e.players[$0.departure].id }, expected.map { $0[0] as! String }, key)
                XCTAssertEqual(list.map { $0.candidate.map { e.players[$0].id } }, expected.map { $0[1] as? String }, key)
                for (a, y) in zip(list, expected) { same(a.similarity, num(y[2]), key) }
            }
        }
    }

    func testCompareMatchesJava() {
        for case let g as [String: Any] in Self.golden["compare"] as? [Any] ?? [] {
            let ids = (g["ids"] as! [String]).map(idx)
            let r = try! e.compare(ids)
            XCTAssertEqual(e.positions[r.position].code, g["position"] as? String)
            XCTAssertEqual(r.role.name, g["role"] as? String)
            let sim = g["similarity"] as! [[Any]]
            for a in ids.indices { for b in ids.indices { same(r.similarity[a][b], num(sim[a][b]), "sim \(a) \(b)") } }
            let players = g["players"] as! [[String: Any]]
            for (col, x) in zip(r.columns, players) {
                let stats = x["stats"] as! [String: [Any]]
                XCTAssertEqual(Set(col.stats.keys.map { e.stats[$0].key }), Set(stats.keys))
                for (st, v) in col.stats {
                    let y = stats[e.stats[st].key]!
                    same(v.z, num(y[0]), "\(e.stats[st].key) z")
                    same(v.per90, num(y[1]), "\(e.stats[st].key) per90")
                    same(v.percentile, num(y[2]), "\(e.stats[st].key) pct")
                }
            }
        }
    }

    func testNamesNormaliseLikeJava() {
        XCTAssertEqual(Names.normalize("  Martin Ødegaard "), "martin odegaard")
        XCTAssertEqual(Names.normalize("Thomas Müller"), "thomas muller")
        XCTAssertEqual(Names.normalize("Łukasz Fabiański"), "lukasz fabianski")
        XCTAssertEqual(Names.normalize("N'Golo  Kanté"), "n golo kante")
        XCTAssertEqual(Names.tokens("—"), [])
    }

    func testEdgeCases() {
        XCTAssertTrue(e.search("   ").isEmpty, "blank query finds nothing")
        XCTAssertThrowsError(try e.plan(departures: [], maxAge: nil), "no departures is an error")
        XCTAssertThrowsError(try e.compare([0]), "one player is not a comparison")
        XCTAssertNil(Matcher.bottleneck(2, 3, [Edge(left: 0, right: 0, score: 90), Edge(left: 1, right: 0, score: 80)]),
                     "two roles, one candidate: infeasible")
        let m = Matcher.bottleneck(2, 3, [Edge(left: 0, right: 0, score: 92), Edge(left: 0, right: 1, score: 90),
                                          Edge(left: 1, right: 0, score: 89), Edge(left: 1, right: 2, score: 89)])!
        XCTAssertEqual(m.bottleneck, 89)
        XCTAssertEqual(m.rightOf, [0, 2], "keeps the strong pair when the weakest is tied")
    }
}
