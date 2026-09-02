// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The commands that make, change, move and remove assets and the folders they live in.
 *
 * <p>Creating an asset is the only mutation with a payload, and it is the only one where the
 * request body is the thing rather than a description of it. The bytes are bounded by the contract,
 * the media type is checked against a closed set rather than sniffed from the content, and a
 * payload whose declared type this build does not accept is refused before anything is written.</p>
 *
 * <p>Nothing here generates renditions. The platform's own workflow does that, afterwards, and
 * claiming it happened because an asset was created would be claiming something these commands
 * cannot observe. The answer says the asset exists and how large its original is; whether its
 * renditions do is a separate question with its own command.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.asset;
