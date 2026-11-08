package com.rideledger;

final class TeamJoiner {
    static final long WAIT_MILLIS = 30000;
    static final long RETURN_MILLIS = 2000;
    static final long HISTORY_WAIT_MILLIS = 8000;
    private final LedgerStore store;

    TeamJoiner(LedgerStore store) { this.store = store; }

    LedgerStore.Trip preview(String code) throws Exception {
        return store.previewInvite(compactCode(code));
    }

    LedgerStore.Trip join(String code, String identity) throws Exception {
        return store.joinTrip(compactCode(code), identity);
    }

    private static String compactCode(String code) {
        String compact = code.replaceAll("\\s+", "");
        if (!InviteQr.isTeamCode(compact)) throw new IllegalArgumentException("请检查二维码或令牌");
        return compact;
    }

    String status(LedgerStore.Trip trip, String rejected, long waited) {
        if (store.teamReady(trip)) return "已成功加入，可返回记账";
        if (!rejected.isEmpty()) return rejected;
        if (waited >= WAIT_MILLIS) return "无法连接到组织者，请确认对方已打开小队页面，再重试";
        return "正在加入小队...";
    }

    String nameConflict(LedgerStore.Trip invitation, String rawName) {
        String name = rawName.trim();
        if (name.isEmpty()) return "";
        if (name.equalsIgnoreCase(invitation.nameOf(invitation.member(0)))) return "请使用与组织者不同的名字";
        LedgerStore.Trip local = store.getTrip(invitation.id);
        if (local != null && local.team != null && local.team.fingerprint.equals(invitation.team.fingerprint)) {
            for (java.util.Map.Entry<String, String> seat : local.seatNames.entrySet()) {
                if (!seat.getKey().equals(local.localPerson) && name.equalsIgnoreCase(seat.getValue()))
                    return "这个名字已登记在另一台设备";
            }
        }
        return "";
    }
}
