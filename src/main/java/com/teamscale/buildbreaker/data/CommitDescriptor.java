/*
 * Copyright (c) CQSE GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teamscale.buildbreaker.data;

import org.conqat.lib.commons.assertion.CCSMAssert;
import org.conqat.lib.commons.string.StringUtils;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable class describing a single commit by its branch name and a timestamp. The timestamp must be unique within
 * the branch. They are comparable by timestamp, where equal timestamps are resolved alphabetically by the branch name.
 */
public class CommitDescriptor implements Serializable, Comparable<CommitDescriptor> {

    /** Version for serialization. */
    private static final long serialVersionUID = 1L;

    /** The name of the branch. */
    private final String branchName;

    /** The timestamp on the branch. */
    private final long timestamp;

    /** Gson constructor. */
    public CommitDescriptor() {
        branchName = null;
        timestamp = 0L;
    }

    public CommitDescriptor(String branchName, long timestamp) {
        CCSMAssert.isTrue(timestamp >= 0, "Timestamp must be >= 0 but is " + timestamp);
        CCSMAssert.isNotNull(branchName);

        this.branchName = branchName;
        this.timestamp = timestamp;
    }

    /**
     * @see #branchName
     */
    public String getBranchName() {
        return branchName;
    }

    /**
     * @see #timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CommitDescriptor) {
            CommitDescriptor other = (CommitDescriptor) obj;
            return other.timestamp == timestamp && Objects.equals(branchName, other.branchName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(branchName) ^ Long.hashCode(timestamp);
    }

    @Override
    public int compareTo(CommitDescriptor other) {
        if (timestamp == other.timestamp) {
            return branchName.compareTo(other.branchName);
        }
        return Long.compare(timestamp, other.timestamp);
    }

    /** branchName + "@" + timestamp */
    @Override
    public String toString() {
        return branchName + "@" + timestamp;
    }

    public String toServiceCallFormat() {
        String result = branchName + ":";
        if ("##no-branch##".contentEquals(Objects.requireNonNull(branchName))) {
            result = StringUtils.EMPTY_STRING;
        }
        if (timestamp == Long.MAX_VALUE) {
            return result + "HEAD";
        }
        return result + timestamp;
    }
}
