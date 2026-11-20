package com.rideledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A snapshot of each member's receivable (positive) or payable (negative) by currency. */
final class LedgerSettlement {
    static final class Debt {
        final String payer, recipient;
        final List<String> amounts;

        Debt(String payer, String recipient, List<String> amounts) {
            this.payer = payer;
            this.recipient = recipient;
            this.amounts = Collections.unmodifiableList(amounts);
        }
    }

    private final List<String> members = new ArrayList<>();
    private final Map<String, long[]> balances = new TreeMap<>();
    private final boolean incomplete;

    LedgerSettlement(LedgerStore.Trip trip, LedgerStore.Summary summary) {
        incomplete = summary.incomplete;
        int people = trip.people();
        for (int i = 0; i < people; i++) {
            String member = trip.member(i);
            members.add(trip.nameOf(member));
            for (Map.Entry<String, Long> entry : summary.teamNet.getOrDefault(member, Collections.emptyMap()).entrySet()) {
                if (entry.getValue() != 0)
                    balances.computeIfAbsent(entry.getKey(), code -> new long[people])[i] = entry.getValue();
            }
        }
    }

    Set<String> currencies() { return Collections.unmodifiableSet(balances.keySet()); }

    boolean sameBalances(LedgerSettlement other) {
        if (incomplete != other.incomplete || !members.equals(other.members) || !currencies().equals(other.currencies()))
            return false;
        for (Map.Entry<String, long[]> entry : balances.entrySet()) {
            if (!Arrays.equals(entry.getValue(), other.balances.get(entry.getKey()))) return false;
        }
        return true;
    }

    List<Debt> byCurrency() {
        Map<Integer, List<String>> payments = new TreeMap<>();
        balances.forEach((code, amounts) -> {
            BigInteger[] remaining = Arrays.stream(amounts).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
            match(remaining, code, payments);
        });
        return debts(payments);
    }

    List<Debt> convert(String target, Map<String, String> quotes) {
        if (incomplete) throw new IllegalArgumentException("结算金额不完整，暂无法折算");
        String code = Money.code(target);
        BigDecimal[] net = new BigDecimal[members.size()];
        Arrays.fill(net, BigDecimal.ZERO);
        for (Map.Entry<String, long[]> entry : balances.entrySet()) {
            String source = entry.getKey();
            BigDecimal rate;
            try {
                rate = source.equals(code) ? BigDecimal.ONE : Money.exchangeRate(quotes.getOrDefault(source, ""));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("请输入 " + source + " 的有效汇率（大于 0）");
            }
            BigDecimal factor = rate.scaleByPowerOfTen(Money.scale(code) - Money.scale(source));
            long[] amounts = entry.getValue();
            for (int i = 0; i < amounts.length; i++) net[i] = net[i].add(factor.multiply(BigDecimal.valueOf(amounts[i])));
        }
        if (Arrays.stream(net).reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0)
            throw new IllegalArgumentException("成员结算金额不平衡，请核对账目");
        BigInteger[] rounded = new BigInteger[net.length];
        Arrays.fill(rounded, BigInteger.ZERO);
        roundSide(net, rounded, 1);
        roundSide(net, rounded, -1);
        Map<Integer, List<String>> payments = new TreeMap<>();
        match(rounded, code, payments);
        return debts(payments);
    }

    /** Round the total once on each side, then assign minor units by the largest remainders. */
    private static void roundSide(BigDecimal[] net, BigInteger[] rounded, int sign) {
        List<Integer> indices = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < net.length; i++) {
            if (net[i].signum() != sign) continue;
            indices.add(i);
            BigDecimal absolute = net[i].abs();
            total = total.add(absolute);
            BigInteger whole = absolute.toBigInteger();
            assigned = assigned.add(whole);
            rounded[i] = sign > 0 ? whole : whole.negate();
        }
        // The unassigned amount is at most one minor unit per member.
        int remainder = total.setScale(0, RoundingMode.HALF_UP).toBigInteger().subtract(assigned).intValue();
        indices.sort(Comparator.<Integer, BigDecimal>comparing(i -> net[i].abs().remainder(BigDecimal.ONE))
                .reversed().thenComparingInt(Integer::intValue));
        for (int i = 0; i < remainder; i++) {
            int member = indices.get(i);
            rounded[member] = rounded[member].add(BigInteger.valueOf(sign));
        }
    }

    private static void match(BigInteger[] remaining, String code, Map<Integer, List<String>> payments) {
        int people = remaining.length;
        for (int debtor = 0; debtor < people; debtor++) {
            for (int creditor = 0; creditor < people && remaining[debtor].signum() < 0; creditor++) {
                if (remaining[creditor].signum() <= 0) continue;
                BigInteger amount = remaining[debtor].negate().min(remaining[creditor]);
                remaining[debtor] = remaining[debtor].add(amount);
                remaining[creditor] = remaining[creditor].subtract(amount);
                payments.computeIfAbsent(debtor * people + creditor, pair -> new ArrayList<>())
                        .add(Money.format(amount, code));
            }
        }
    }

    private List<Debt> debts(Map<Integer, List<String>> payments) {
        int people = members.size();
        List<Debt> result = new ArrayList<>();
        payments.forEach((pair, amounts) -> result.add(new Debt(members.get(pair / people), members.get(pair % people), amounts)));
        return Collections.unmodifiableList(result);
    }
}
