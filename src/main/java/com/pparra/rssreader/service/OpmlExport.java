package com.pparra.rssreader.service;

/** The OPML document plus how many sources it holds and how many non-feed sources were left out of it. */
public record OpmlExport(String xml, int exported, int leftOut) {
}
