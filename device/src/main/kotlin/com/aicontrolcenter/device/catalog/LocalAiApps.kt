package com.aicontrolcenter.device.catalog

import com.aicontrolcenter.device.catalog.IdEvidence.STORE_LISTING
import com.aicontrolcenter.device.catalog.IdEvidence.VENDOR_SOURCE
import com.aicontrolcenter.device.catalog.KnownAiAppKind.API_CLIENT
import com.aicontrolcenter.device.catalog.KnownAiAppKind.LOCAL_RUNNER
import com.aicontrolcenter.device.catalog.KnownAiAppKind.TERMINAL

private fun runner(packageName: String, name: String, vendor: String, evidence: IdEvidence = VENDOR_SOURCE) =
    KnownAiApp(packageName, name, vendor, LOCAL_RUNNER, evidence)

private fun client(packageName: String, name: String, vendor: String) =
    KnownAiApp(packageName, name, vendor, API_CLIENT, VENDOR_SOURCE)

/**
 * Apps that run models on the phone, clients for the user's own model servers, and Termux.
 *
 * Almost all of these are open source, so their ids were read from the projects' build files
 * and the vendor is the maintainer named by the repository. Package ids are case-sensitive
 * (`com.Vali98.ChatterUI`).
 */
internal val localAiApps: List<KnownAiApp> = listOf(
    runner("com.google.ai.edge.gallery", "Google AI Edge Gallery", "Google"),
    // The id produced by building the Gallery from its public source differs from the Play build.
    runner("com.google.aiedge.gallery", "Google AI Edge Gallery (source build)", "Google"),
    runner("com.pocketpalai", "PocketPal AI", "a-ghorbani"),
    runner("ai.mlc.mlcchat", "MLC Chat", "MLC AI"),
    runner("com.layla", "Layla", "Layla", STORE_LISTING),
    runner("com.Vali98.ChatterUI", "ChatterUI", "Vali-98"),
    runner("com.danemadsen.maid", "Maid", "Mobile Artificial Intelligence"),
    runner("ai.offgridmobile", "Off Grid", "alichherawalla"),
    runner("io.shubham0204.smollmandroid", "SmolChat", "shubham0204"),
    runner("com.alibaba.mnnllm.android", "MNN Chat", "Alibaba"),
    runner("com.druk.lmplayground", "LM Playground", "andriydruk"),
    runner("com.llamatik.app.android", "Llamatik", "ferranpons"),
    runner("com.anythingllm", "AnythingLLM", "Mintplex Labs", STORE_LISTING),
    // A third-party client for a remote Ollama server. Ollama itself has no Android app; on a
    // phone it runs inside Termux.
    client("com.freakurl.apps.ollama", "Ollama App", "JHubi1"),
    client("me.rerere.rikkahub", "RikkaHub", "RikkaHub"),
    client("dev.chungjungsoo.gptmobile", "GPTMobile", "chocydar"),
    client("app.cogwheel.conduit", "Conduit", "cogwheel0"),
    client("ai.openclaw.app", "OpenClaw", "OpenClaw"),
    KnownAiApp("com.termux", "Termux", "Termux", TERMINAL, VENDOR_SOURCE),
)
