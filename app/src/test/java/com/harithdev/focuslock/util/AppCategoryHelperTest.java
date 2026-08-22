package com.harithdev.focuslock.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AppCategoryHelperTest {

    @Test
    public void testCategorizeSocial() {
        assertEquals("Social", AppCategoryHelper.categorize("com.instagram.android"));
        assertEquals("Social", AppCategoryHelper.categorize("com.twitter.android"));
        assertEquals("Social", AppCategoryHelper.categorize("com.facebook.katana"));
        assertEquals("Social", AppCategoryHelper.categorize("com.snapchat.android"));
        assertEquals("Social", AppCategoryHelper.categorize("com.reddit.frontpage"));
    }

    @Test
    public void testCategorizeVideo() {
        assertEquals("Video", AppCategoryHelper.categorize("com.google.android.youtube"));
        assertEquals("Video", AppCategoryHelper.categorize("com.netflix.mediaclient"));
        assertEquals("Video", AppCategoryHelper.categorize("com.disney.disneyplus"));
        assertEquals("Video", AppCategoryHelper.categorize("tv.twitch.android.app"));
    }

    @Test
    public void testCategorizeMessaging() {
        assertEquals("Messaging", AppCategoryHelper.categorize("com.whatsapp"));
        assertEquals("Messaging", AppCategoryHelper.categorize("org.telegram.messenger"));
        assertEquals("Messaging", AppCategoryHelper.categorize("com.discord"));
    }

    @Test
    public void testCategorizeGaming() {
        assertEquals("Gaming", AppCategoryHelper.categorize("com.supercell.clashofclans"));
        assertEquals("Gaming", AppCategoryHelper.categorize("com.roblox.client"));
        assertEquals("Gaming", AppCategoryHelper.categorize("com.mojang.minecraftpe"));
    }

    @Test
    public void testCategorizeProductivity() {
        assertEquals("Productivity", AppCategoryHelper.categorize("com.Slack"));
        assertEquals("Productivity", AppCategoryHelper.categorize("notion.id"));
        assertEquals("Productivity", AppCategoryHelper.categorize("com.trello"));
    }

    @Test
    public void testCategorizeOther() {
        assertEquals("Other", AppCategoryHelper.categorize("com.cargills.online"));
        assertEquals("Other", AppCategoryHelper.categorize("com.random.utility.app"));
        assertEquals("Other", AppCategoryHelper.categorize(null));
    }
}
