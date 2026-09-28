package org.telegram.messenger;

public final class AgramNetworkPolicyTest {
    public static void main(String[] args) {
        assert AgramNetworkPolicy.allowDirect("direct", true, false);
        for (String mode : new String[]{null, "", "proxy", "tor", "invalid"}) {
            assert !AgramNetworkPolicy.allowDirect(mode, true, false);
        }
        assert !AgramNetworkPolicy.allowDirect("direct", false, false);
        assert !AgramNetworkPolicy.allowDirect("direct", true, true);
        assert !AgramNetworkPolicy.allowDirect("proxy", false, true);
        System.out.println("PASS AgramNetworkPolicy: selected route, inaccessible storage, quarantined auth, unknown modes");
    }
}
