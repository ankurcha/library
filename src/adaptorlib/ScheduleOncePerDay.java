// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package adaptorlib;

import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;

/**
 * ScheduleOncePerDay provides a sequence of Dates 1 day
 * apart at the same hour/minute/second each day.
 * <p>
 * Gratitude to
 * http://www.ibm.com/developerworks/java/library/j-schedule/index.html
 */
public class ScheduleOncePerDay implements Iterator<Date> {
  private final int hourOfDay, minute, second;
  private final Calendar calendar = Calendar.getInstance();

  public ScheduleOncePerDay(int hourOfDay, int minute, int second) {
    this(hourOfDay, minute, second, new Date());
  }

  public ScheduleOncePerDay(int hourOfDay, int minute, int second, Date date) {
    this.hourOfDay = hourOfDay;
    this.minute = minute;
    this.second = second;
    calendar.setTime(date);
    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
    calendar.set(Calendar.MINUTE, minute);
    calendar.set(Calendar.SECOND, second);
    calendar.set(Calendar.MILLISECOND, 0);
    if (!calendar.getTime().before(date)) {
      calendar.add(Calendar.DATE, -1);
    }
  }

  public boolean hasNext() {
    return true;
  }

  public Date next() {
    calendar.add(Calendar.DATE, 1);
    return calendar.getTime();
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }
}
