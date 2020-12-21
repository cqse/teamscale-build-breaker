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

package com.cqse.teamscalefeedback.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.conqat.lib.commons.assertion.CCSMAssert;
import org.conqat.lib.commons.io.ByteArrayUtils;
import org.conqat.lib.commons.string.StringUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * Immutable class describing a single commit by its branch name and a
 * timestamp. The timestamp must be unique within the branch. They are
 * comparable by timestamp, where equal timestamps are resolved alphabetically
 * by the branch name.
 */
public class CommitDescriptor implements Serializable, Comparable<CommitDescriptor> {

    /**
     * The separator used between branch name and timestamp in
     * {@link #toBranchTimestampKeyWithSeparator()}.
     */
    public static final byte[] SEPARATOR = {1, 2, 1};

    /**
     * Name of the branch if no branch is specified.
     */
    // HistoryAccessOption is not accessible in all project where this class is
    // used as external source file
    @SuppressWarnings("javadoc")
    private static final String NO_BRANCH_NAME = "##no-branch##";

    /** Version for serialization. */
    private static final long serialVersionUID = 1L;

    /** Special value to indicate the HEAD of a branch. */
    public static final String HEAD_TIMESTAMP = "HEAD";

    /**
     * A comparator for comparison by timestamps. Null commits are ordered to the
     * front.
     * <p>
     * If there are commits with identical timestamps, we order them by comparing
     * the branch names to guarantee a stable order. This should only happen in
     * special cases since teamscale ensures that no timestamp is given to two
     * commits.
     */
    public static final Comparator<CommitDescriptor> BY_TIMESTAMP_COMPARATOR = Comparator.nullsFirst(
            Comparator.comparingLong(CommitDescriptor::getTimestamp).thenComparing(CommitDescriptor::getBranchName));

    /** The name of the JSON property name for {@link #branchName}. */
    protected static final String BRANCH_NAME_PROPERTY = "branchName";

    /** The name of the JSON property name for {@link #timestamp}. */
    protected static final String TIMESTAMP_PROPERTY = "timestamp";

    /** The name of the branch. */
    @JsonProperty(BRANCH_NAME_PROPERTY)
    private final String branchName;

    /** The timestamp on the branch. */
    @JsonProperty(TIMESTAMP_PROPERTY)
    private final long timestamp;

    /**
     * Constructor. <br/>
     * use {@link CommitDescriptor#createUnbranchedDescriptor(long)} to create a
     * unbranched commit descriptor.
     */
    @JsonCreator
    public CommitDescriptor(@JsonProperty(BRANCH_NAME_PROPERTY) String branchName,
                            @JsonProperty(TIMESTAMP_PROPERTY) long timestamp) {
        CCSMAssert.isTrue(timestamp >= 0, "Timestamp must be >= 0 but is " + timestamp);
        CCSMAssert.isNotNull(branchName);

        this.branchName = branchName;
        this.timestamp = timestamp;
    }

    public CommitDescriptor(CommitDescriptor other) {
        this(other.branchName, other.timestamp);
    }

    /**
     * Create a {@link CommitDescriptor} without branch specification. <br/>
     * Should be used with caution.
     */
    public static CommitDescriptor createUnbranchedDescriptor(long timestamp) {
        return new CommitDescriptor(NO_BRANCH_NAME, timestamp);
    }

    /**
     * Create a copy of this commit descriptor with <code>timestamp - 1</code>.
     * <p>
     * Using this CommitDescriptor to store a new commit might overwrite an existing
     * commit if the timestamp is already used.
     */
    public CommitDescriptor cloneWithDecrementedTimestamp() {
        return new CommitDescriptor(this.branchName, this.timestamp - 1);
    }

    /**
     * Create a copy of this commit descriptor with <code>timestamp + 1</code>.
     * <p>
     * Using this CommitDescriptor to store a new commit might overwrite an existing
     * commit if the timestamp is already used.
     */
    public CommitDescriptor cloneWithIncrementedTimestamp() {
        return new CommitDescriptor(this.branchName, this.timestamp + 1);
    }

    /**
     * @see #branchName
     */
    public String getBranchName() {
        return branchName;
    }

    /** Return true if no branch is specified. */
    public boolean isUnbranched() {
        return NO_BRANCH_NAME.equals(this.branchName);
    }

    /** Returns whether this commit is a head timestamp */
    public boolean isHeadCommit() {
        return timestamp == Long.MAX_VALUE;
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

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hashCode(branchName) ^ Long.hashCode(timestamp);
    }

    /** {@inheritDoc} */
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

    /** Returns a timestamp+branchName key/byte[] representation. */
    public byte[] toTimestampBranchKey() {
        return ByteArrayUtils.concat(ByteArrayUtils.longToByteArray(getTimestamp()),
                StringUtils.stringToBytes(getBranchName()));
    }

    /** Parses a commit descriptor from its toString() representation. */
    public static CommitDescriptor fromStringRepresentation(String representation) {
        int separatorPosition = representation.lastIndexOf("@");
        CCSMAssert.isTrue(separatorPosition >= 0,
                () -> "Invalid string representation of commit descriptor: " + representation);
        String branch = representation.substring(0, separatorPosition);
        String timestamp = representation.substring(separatorPosition + 1);
        return new CommitDescriptor(branch, Long.parseLong(timestamp));
    }

    /** Parses a timestamp+branchName key/byte[] representation. */
    public static CommitDescriptor fromTimestampBranchKey(byte[] key) {
        long timestamp = ByteArrayUtils.byteArrayToLong(Arrays.copyOf(key, Long.BYTES));
        String branchName = StringUtils.bytesToString(Arrays.copyOfRange(key, Long.BYTES, key.length));
        return new CommitDescriptor(branchName, timestamp);
    }

    /**
     * Returns a branchName+timestamp key/byte[] representation. NOTE: This is
     * *DANGEROUS* since keys generated with this function may cause unwanted branch
     * names (prefixes of wanted branch names) to be returned from store scans
     * (TS-16367). To protect against this, use
     * {@link #toBranchTimestampKeyWithSeparator()}.
     */
    public byte[] toBranchTimestampKey() {
        return ByteArrayUtils.concat(StringUtils.stringToBytes(getBranchName()),
                ByteArrayUtils.longToByteArray(getTimestamp()));
    }

    /**
     * Returns a branchName+timestamp key/byte[] representation with
     * {@link #SEPARATOR} in between.
     */
    public byte[] toBranchTimestampKeyWithSeparator() {
        return ByteArrayUtils.concat(StringUtils.stringToBytes(getBranchName()), SEPARATOR,
                ByteArrayUtils.longToByteArray(getTimestamp()));
    }

    /** Parses a branch+timestamp key/byte[] representation with separator. */
    public static CommitDescriptor fromBranchTimestampKeyWithSeparator(byte[] key) {
        String branchName = StringUtils.bytesToString(Arrays.copyOf(key, key.length - Long.BYTES - SEPARATOR.length));
        long timestamp = ByteArrayUtils.byteArrayToLong(Arrays.copyOfRange(key, key.length - Long.BYTES, key.length));
        return new CommitDescriptor(branchName, timestamp);
    }

    /** Parses a branch+timestamp key/byte[] representation. */
    public static CommitDescriptor fromBranchTimestampKey(byte[] key) {
        String branchName = StringUtils.bytesToString(Arrays.copyOf(key, key.length - Long.BYTES));
        long timestamp = ByteArrayUtils.byteArrayToLong(Arrays.copyOfRange(key, key.length - Long.BYTES, key.length));
        return new CommitDescriptor(branchName, timestamp);
    }

    /** Creates a commit descriptor for the latest revision on a branch */
    public static CommitDescriptor latestOnBranch(String branchName) {
        return new CommitDescriptor(branchName, Long.MAX_VALUE);
    }

    /**
     * Returns a format used for service calls ("branch:timestamp", or "timestamp"
     * if no branch is specified).
     */
    public String toServiceCallFormat() {
        String result = branchName + ":";
        if (NO_BRANCH_NAME.contentEquals(branchName)) {
            result = StringUtils.EMPTY_STRING;
        }

        if (timestamp == Long.MAX_VALUE) {
            return result + HEAD_TIMESTAMP;
        }
        return result + timestamp;
    }

}
