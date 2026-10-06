/*
 * Copyright (C) 2026 The ArkUI Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app;

/**
 * Lock-free application lock state for other system services.
 * @hide
 */
public abstract class AppLockManagerInternal {
    public interface Listener {
        void onAppLockStateChanged(int userId);
    }

    public abstract boolean isPackageProtected(String packageName, int userId);
    public abstract boolean isPackageLocked(String packageName, int userId);
    public abstract boolean shouldHideNotifications(String packageName, int userId);
    public abstract void addListener(Listener listener);
    public abstract void removeListener(Listener listener);
}
