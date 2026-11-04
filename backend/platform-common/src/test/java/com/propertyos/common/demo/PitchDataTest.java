package com.propertyos.common.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PitchDataTest {

    @Test
    @DisplayName("pitch data may only be written when demo mode and the pitch switch are both on")
    void guard() {
        assertThat(PitchData.enabled(true, true)).isTrue();
        assertThat(PitchData.enabled(true, false)).isFalse();
        assertThat(PitchData.enabled(false, true)).isFalse();   // production with the switch left on by mistake
        assertThat(PitchData.enabled(false, false)).isFalse();
    }

    @Test
    @DisplayName("there is one company on each plan: the new Starter one and the two existing demo companies")
    void plans() {
        assertThat(PitchData.CEDAR.plan()).isEqualTo("STARTER");
        assertThat(DemoData.HARBOR.plan()).isEqualTo("GROWTH");
        assertThat(DemoData.SUMMIT.plan()).isEqualTo("ENTERPRISE");
        assertThat(PitchData.orgIds()).containsExactlyInAnyOrder(PitchData.CEDAR.id(), DemoData.HARBOR.id(), DemoData.SUMMIT.id());
        assertThat(DemoData.orgs()).as("the new company is not added to the standard demo data").doesNotContain(PitchData.CEDAR);
    }

    @Test
    @DisplayName("every id is unique, and ids are the same on every run (that is what makes a second run harmless)")
    void ids() {
        List<UUID> all = new java.util.ArrayList<>();
        PitchData.buildings().forEach(b -> { all.add(b.property().id()); b.units().forEach(u -> all.add(u.id())); });
        PitchData.listings().forEach(l -> all.add(l.id()));
        PitchData.reviews().forEach(r -> all.add(r.id()));
        PitchData.leads().forEach(l -> all.add(l.id()));
        PitchData.applications().forEach(a -> all.add(a.id()));
        PitchData.showings().forEach(s -> all.add(s.id()));
        PitchData.campaigns().forEach(c -> all.add(c.id()));
        PitchData.boosts().forEach(b -> { all.add(b.id()); all.add(b.invoiceId()); });
        assertThat(new HashSet<>(all)).hasSameSizeAs(all);
        assertThat(PitchData.RIVERSIDE.property().id()).isEqualTo(DemoData.id("pitch:property:riverside"));
        assertThat(PitchData.listing("riverside:102").id()).isEqualTo(DemoData.id("pitch:listing:riverside:102"));
        // The same rater can never rate the same thing twice (the table forbids it), and ids must not collide with the standard demo data.
        Set<String> raters = PitchData.reviews().stream().map(r -> r.subjectType() + r.subjectId() + r.raterUserId()).collect(Collectors.toSet());
        assertThat(raters).hasSameSizeAs(PitchData.reviews());
    }

    @Test
    @DisplayName("each company's buildings have listings with several photos, except the thin Starter listings, which are meant to be improved")
    void listings() {
        for (PitchData.Listing l : PitchData.listings()) {
            assertThat(l.photos()).as(l.key()).isNotEmpty().doesNotHaveDuplicates();
            assertThat(l.title()).isNotBlank();
            assertThat(l.orgId()).isEqualTo(l.building().orgId());
        }
        assertThat(PitchData.listing("riverside:102").photos()).hasSize(5);
        assertThat(PitchData.listing("riverside:102").description().length()).isGreaterThan(300);
        assertThat(PitchData.listing("cedar:1").photos()).hasSize(2);
        assertThat(PitchData.listing("cedar:1").description().length()).isLessThan(120);
        assertThat(PitchData.listings().stream().filter(l -> l.status().equals("LIVE"))).hasSizeGreaterThanOrEqualTo(10);
        for (UUID org : PitchData.orgIds()) assertThat(PitchData.listings().stream().filter(l -> l.orgId().equals(org) && l.status().equals("LIVE"))).as(org.toString()).isNotEmpty();
    }

    private static double average(List<PitchData.Review> rs) {
        return rs.stream().mapToInt(PitchData.Review::stars).average().orElse(0);
    }

    @Test
    @DisplayName("ratings tell three stories: a loved building that earns the Top rated badge, a good one, and a new one with few ratings")
    void ratings() {
        List<PitchData.Review> riverside = PitchData.reviews().stream().filter(r -> r.subjectType().equals("PROPERTY") && r.propertyId().equals(PitchData.RIVERSIDE.property().id())).toList();
        List<PitchData.Review> bayline = PitchData.reviews().stream().filter(r -> r.subjectType().equals("PROPERTY") && r.propertyId().equals(PitchData.BAYLINE.property().id())).toList();
        List<PitchData.Review> cedar = PitchData.reviews().stream().filter(r -> r.subjectType().equals("PROPERTY") && r.propertyId().equals(PitchData.CEDAR_COTTAGES.property().id())).toList();
        assertThat(riverside).hasSize(19);
        assertThat(average(riverside)).isGreaterThanOrEqualTo(4.5);            // three or more ratings averaging 4.5 shows Top rated
        assertThat(bayline).hasSize(7);
        assertThat(average(bayline)).isBetween(3.8, 4.4);
        assertThat(cedar).hasSize(4);
        assertThat(average(cedar)).isBetween(3.8, 4.4);
        assertThat(PitchData.reviews().stream().filter(r -> r.stars() < 1 || r.stars() > 5)).isEmpty();
        assertThat(PitchData.reviews().stream().map(PitchData.Review::raterName).collect(Collectors.toSet()).size()).isGreaterThan(12);
        // The same name must not appear twice among one subject's ratings, or a building's reviews look made up.
        for (PitchData.Building b : PitchData.buildings()) {
            List<String> names = PitchData.reviews().stream().filter(r -> r.subjectType().equals("PROPERTY") && r.propertyId().equals(b.property().id())).map(PitchData.Review::raterName).toList();
            assertThat(names).as(b.key()).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("exactly one comment is held back by moderation (score under 70), and its stars still count")
    void moderation() {
        List<PitchData.Review> held = PitchData.reviews().stream().filter(r -> r.aiScore() < 70).toList();
        assertThat(held).hasSize(1);
        assertThat(held.get(0).stars()).isEqualTo(1);
        assertThat(held.get(0).comment()).containsIgnoringCase("idiot");
        assertThat(held.get(0).reply()).isNull();
        assertThat(PitchData.reviews().stream().filter(r -> r.aiScore() >= 70)).hasSizeGreaterThan(30);
    }

    @Test
    @DisplayName("some reviews have a public reply from the company, each by a named member of staff, never before the review")
    void replies() {
        List<PitchData.Review> replied = PitchData.reviews().stream().filter(r -> r.reply() != null).toList();
        assertThat(replied).hasSizeGreaterThanOrEqualTo(8);
        for (PitchData.Review r : replied) {
            assertThat(r.replyBy()).isNotBlank();
            assertThat(r.replyDaysAgo()).isLessThanOrEqualTo(r.daysAgo());
        }
        assertThat(PitchData.reviews().stream().filter(r -> r.reply() == null && r.replyBy() != null)).isEmpty();
    }

    @Test
    @DisplayName("Summit Ridge has an Enterprise listing with a boost running now, and a finished one for history")
    void boosts() {
        assertThat(PitchData.ACTIVE_BOOST.status()).isEqualTo("ACTIVE");
        assertThat(PitchData.ACTIVE_BOOST.startedDaysAgo()).isLessThan(PitchData.ACTIVE_BOOST.days());   // still inside its period
        assertThat(PitchData.listing(PitchData.ACTIVE_BOOST.listingKey()).orgId()).isEqualTo(DemoData.SUMMIT.id());
        assertThat(PitchData.listing(PitchData.ACTIVE_BOOST.listingKey()).status()).isEqualTo("LIVE");
        assertThat(PitchData.PAST_BOOST.status()).isEqualTo("EXPIRED");
        assertThat(PitchData.PAST_BOOST.startedDaysAgo()).isGreaterThan(PitchData.PAST_BOOST.days());
        assertThat(PitchData.boosts().stream().map(PitchData.Boost::invoiceNumber)).doesNotHaveDuplicates().allMatch(n -> n.length() <= 24);
    }

    @Test
    @DisplayName("two campaigns, a Facebook one and a Zillow one, each with leads at different stages and distinct keys")
    void campaigns() {
        assertThat(PitchData.FACEBOOK.channel()).isEqualTo("FACEBOOK_ADS");
        assertThat(PitchData.ZILLOW.channel()).isEqualTo("ZILLOW");
        assertThat(PitchData.campaigns().stream().map(PitchData.Campaign::publicKey)).doesNotHaveDuplicates();
        assertThat(PitchData.campaigns().stream().map(PitchData.Campaign::secret)).doesNotHaveDuplicates();
        for (PitchData.Campaign c : PitchData.campaigns()) {
            assertThat(c.trackingCode()).matches("[a-z0-9]{1,16}");
            assertThat(c.publicKey().length()).isLessThanOrEqualTo(40);
            assertThat(c.secret().length()).isLessThanOrEqualTo(80);
        }
        for (PitchData.Campaign c : PitchData.campaigns()) {
            List<PitchData.Lead> leads = PitchData.leads().stream().filter(l -> l.campaign().equals(c)).toList();
            assertThat(leads.size()).isGreaterThanOrEqualTo(7);
            assertThat(leads.stream().map(PitchData.Lead::status).collect(Collectors.toSet())).containsExactlyInAnyOrder("NEW", "RESPONDED");
            assertThat(leads.stream().map(PitchData.Lead::daysAgo).max(Integer::compare).orElse(0)).isGreaterThan(14);   // spread over weeks, so the chart has a shape
            assertThat(leads.stream().map(PitchData.Lead::score).collect(Collectors.toSet()).size()).isGreaterThan(4);
        }
        assertThat(PitchData.leads().stream().map(PitchData.Lead::email)).doesNotHaveDuplicates();
        for (PitchData.Lead l : PitchData.leads()) {
            assertThat(l.listing().status()).isIn("LIVE", "LEASED");
            assertThat(l.listing().orgId()).isEqualTo(l.campaign().orgId());   // a campaign only ever brings in leads for its own company's homes
        }
    }

    @Test
    @DisplayName("one lead has toured and is ready to become an application, and has no application yet")
    void readyToApply() {
        PitchData.Lead tessa = PitchData.lead(PitchData.READY_TO_APPLY);
        assertThat(tessa.intent()).isEqualTo("APPLY");
        assertThat(tessa.score()).isGreaterThanOrEqualTo(90);
        assertThat(PitchData.showings().stream().anyMatch(s -> s.lead().equals(tessa) && s.status().equals("ATTENDED"))).isTrue();
        assertThat(PitchData.applications().stream().noneMatch(a -> a.lead().equals(tessa))).isTrue();
        // and no one else who is ready to apply is hidden: everyone with an application also has a lead
        for (PitchData.Application a : PitchData.applications()) assertThat(PitchData.leads()).contains(a.lead());
    }

    @Test
    @DisplayName("applications are at different stages, and the approved one belongs to the resident who is moving in")
    void applications() {
        assertThat(PitchData.applications().stream().map(PitchData.Application::status).collect(Collectors.toSet())).hasSizeGreaterThanOrEqualTo(4);
        PitchData.Application jamie = PitchData.applications().stream().filter(a -> a.personId().equals(PitchData.JAMIE_ID)).findFirst().orElseThrow();
        assertThat(jamie.status()).isEqualTo("APPROVED");
        assertThat(jamie.lead().email()).isEqualTo(PitchData.JAMIE_EMAIL);
        assertThat(jamie.lead().campaign()).isEqualTo(PitchData.FACEBOOK);   // the lease, and so the conversion, comes from the Facebook campaign
        assertThat(jamie.lead().listing().key()).isEqualTo(PitchData.LEASE_LISTING);
        assertThat(PitchData.applications().stream().map(PitchData.Application::personId)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the resident moving in starts in a few days, so the move-in charges are still open")
    void resident() {
        assertThat(PitchData.LEASE_STARTS_IN_DAYS).isBetween(1, 14);
        DemoData.User jamie = PitchData.users().stream().filter(u -> u.partyId() != null).findFirst().orElseThrow();
        assertThat(jamie.roleKey()).isEqualTo("TENANT");
        assertThat(jamie.partyId()).isEqualTo(PitchData.JAMIE_ID);
        assertThat(jamie.orgId()).isEqualTo(DemoData.SUMMIT.id());
        assertThat(PitchData.listing(PitchData.LEASE_LISTING).status()).isEqualTo("LEASED");
    }

    @Test
    @DisplayName("sign-ins: every pitch user is unique, belongs to a pitch company, uses a real role, and none clash with the standard demo people")
    void users() {
        Set<String> standard = DemoData.users().stream().map(DemoData.User::email).collect(Collectors.toSet());
        Set<UUID> standardIds = DemoData.users().stream().map(DemoData.User::id).collect(Collectors.toSet());
        for (DemoData.User u : PitchData.users()) {
            assertThat(standard).doesNotContain(u.email());
            assertThat(standardIds).doesNotContain(u.id());
            assertThat(PitchData.orgIds()).contains(u.orgId());
            assertThat(u.roleKey()).isIn("ORG_ADMIN", "BROKER", "LEASING_AGENT", "TENANT");
        }
        assertThat(PitchData.users().stream().map(DemoData.User::email)).doesNotHaveDuplicates();
        assertThat(PitchData.users().stream().map(DemoData.User::orgId).collect(Collectors.toSet())).containsExactlyInAnyOrder(PitchData.CEDAR.id(), DemoData.HARBOR.id(), DemoData.SUMMIT.id());
        // the sign-in guide names only people who exist: the new ones above and the standard demo people
        Set<String> known = new HashSet<>(standard);
        PitchData.users().forEach(u -> known.add(u.email()));
        for (var row : PitchData.signIns()) assertThat(known).contains(row.get("email"));
    }
}
