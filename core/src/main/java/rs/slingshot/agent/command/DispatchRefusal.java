// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

/**
 * Why a command could not be dispatched, each cause distinct because each has a different fix.
 */
public enum DispatchRefusal {

    /** A row declares a command and nothing here runs it. */
    ROW_WITH_NO_HANDLER,

    /** Something here runs a command no row declares. */
    HANDLER_WITH_NO_ROW,

    /** Two handlers claim one wire name, so which one runs would be whichever was registered last. */
    TWO_HANDLERS_FOR_ONE_NAME,

    /** A handler can fail in a way its own row does not declare. */
    CATEGORY_NO_ROW_DECLARES,

    /** A row declares a way of failing no handler can produce. */
    CATEGORY_NO_HANDLER_PRODUCES,

    /** The submission's five-field identity was not verified before dispatch was asked for. */
    IDENTITY_NOT_VERIFIED
}
