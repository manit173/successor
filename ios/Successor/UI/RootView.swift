import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.phase {
        case .loading:
            LoadingView()
        case .failed(let message):
            ContentUnavailableView("Couldn't load the data", systemImage: "exclamationmark.triangle", description: Text(message))
        case .ready:
            Tabs()
        }
    }
}

private struct LoadingView: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.pitch, Palette.pitchDeep], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "arrow.right.circle").font(.system(size: 64, weight: .semibold)).foregroundStyle(.white)
                Text("Successor").font(.largeTitle.bold()).foregroundStyle(.white)
                ProgressView().tint(.white)
                Text("Loading players…").font(.footnote).foregroundStyle(.white.opacity(0.8))
            }
        }
    }
}

private struct Tabs: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            Tab("Players", systemImage: "magnifyingglass", value: AppModel.Tab.home) { HomeView() }
            Tab("Successor", systemImage: "arrow.triangle.swap", value: AppModel.Tab.successor) { SuccessorView() }
            Tab("Compare", systemImage: "square.split.2x1", value: AppModel.Tab.compare) { CompareView() }
            Tab("Squad", systemImage: "person.3", value: AppModel.Tab.squad) { SquadView() }
            Tab("About", systemImage: "info.circle", value: AppModel.Tab.about) { AboutView() }
        }
    }
}

// MARK: - Home: search and shortcuts

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var path: [Int] = []

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if let e = model.engine {
                    if query.trimmingCharacters(in: .whitespaces).isEmpty {
                        ScrollView { HomeDashboard(engine: e).padding() }
                            .background(Color(.systemGroupedBackground))
                    } else {
                        List(e.search(query, limit: 40)) { hit in
                            NavigationLink(value: hit.player) {
                                PlayerRowView(player: hit.player,
                                              detail: hit.matchedAlias.map { "also “\($0)”" })
                            }
                        }
                        .overlay {
                            if e.search(query, limit: 1).isEmpty {
                                ContentUnavailableView.search(text: query)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Successor")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search \(model.engine?.players.count.formatted() ?? "") players")
            .navigationDestination(for: Int.self) { PlayerView(player: $0) }
            .onChange(of: model.openPlayer) { if let p = model.openPlayer { model.openPlayer = nil; path = [p] } }
            .onAppear { if let p = model.openPlayer { model.openPlayer = nil; path = [p] } }
        }
    }
}

private struct HomeDashboard: View {
    @Environment(AppModel.self) private var model
    let engine: Engine

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Find who could replace a player at his peak")
                    .font(.title2.bold()).foregroundStyle(.white)
                Text("Players are compared within their position and role, against Big-5 players, with next season projected.")
                    .font(.subheadline).foregroundStyle(.white.opacity(0.85))
                HStack(spacing: 10) {
                    Stat(value: engine.players.count.formatted(), label: "players")
                    Stat(value: engine.seasons.count.formatted(), label: "seasons")
                    Stat(value: "\(Set(engine.seasons.map(\.league)).count)", label: "leagues")
                }
                .padding(.top, 4)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Palette.pitch, Palette.pitchDeep], startPoint: .topLeading,
                                       endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24, style: .continuous))

            if engine.isSample {
                NoteBadge(text: "Synthetic sample data: the real dataset is added on the Mac (scripts/export_phone.sh).")
            }

            Text("Try").font(.headline).padding(.top, 4)
            VStack(spacing: 10) {
                if let kdb = engine.playerIndex["tm:88755"] ?? busiest(at: "AM") {
                    Shortcut(icon: "arrow.triangle.swap", title: "Find \(engine.players[kdb].name)'s successor",
                             subtitle: "Peak profile vs every projected 2026/27") { model.findSuccessor(of: kdb) }
                }
                if let cherki = engine.playerIndex["tm:607223"] ?? busiest(at: "W") {
                    NavigationLink(value: cherki) {
                        ShortcutLabel(icon: "person.crop.circle", title: engine.players[cherki].name,
                                      subtitle: "Role, percentiles, career and projection")
                    }
                    .buttonStyle(.plain)
                }
                let trio = ["tm:88755", "tm:357565", "tm:241641"].compactMap { engine.playerIndex[$0] }
                if trio.count == 3 {
                    Shortcut(icon: "person.3", title: "De Bruyne, Rodri and Bernardo leave",
                             subtitle: "One different replacement each") {
                        model.planSquad(trio)
                    }
                }
            }
        }
    }

    private func busiest(at code: String) -> Int? {
        guard let pos = engine.position(code: code) else { return nil }
        var best: (Int, Double)? = nil
        for (i, s) in engine.seasons.enumerated() where s.season == engine.currentSeason - 1 && engine.positionOf(i) == pos {
            let m = engine.minutes(i)
            if best == nil || m > best!.1 { best = (s.player, m) }
        }
        return best?.0
    }

    private struct Stat: View {
        let value: String
        let label: String
        var body: some View {
            VStack(alignment: .leading, spacing: 0) {
                Text(value).font(.headline.monospacedDigit()).foregroundStyle(.white)
                Text(label).font(.caption2).foregroundStyle(.white.opacity(0.8))
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

private struct Shortcut: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void
    var body: some View {
        Button(action: action) { ShortcutLabel(icon: icon, title: title, subtitle: subtitle) }.buttonStyle(.plain)
    }
}

private struct ShortcutLabel: View {
    let icon: String
    let title: String
    let subtitle: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.title3).foregroundStyle(Palette.pitch)
                .frame(width: 42, height: 42).background(Palette.pitch.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
