package com.teamscale.buildbreaker;

import com.google.gson.InstanceCreator;
import com.teamscale.buildbreaker.data.CommitDescriptor;

import java.lang.reflect.Type;

public class CommitDescriptorInstanceCreator implements InstanceCreator<CommitDescriptor> {
    @Override
    public CommitDescriptor createInstance(Type type) {
        return new CommitDescriptor();
    }
}
