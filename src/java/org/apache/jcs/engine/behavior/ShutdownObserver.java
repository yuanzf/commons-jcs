package org.apache.jcs.engine.behavior;

/*
 * Copyright 2001-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

/**
 * This interface is required of all shutdown observers.  These observers
 * can observer ShutdownObservable objects.  The CacheManager is the primary
 * observable that this is intended for.
 * <p>
 * Most shutdown operations will occur outside this framework for now.  The initial
 * goal is to allow background threads that are not reachable through any reference
 * that the cahe manager maintains to be killed on shutdown.
 * 
 * @author Aaron Smuts
 *
 */
public interface ShutdownObserver
{

    /**
     * Tells the observer that the observable has received a shutdown command.
     *
     */
    abstract void shutdown();
    
}
