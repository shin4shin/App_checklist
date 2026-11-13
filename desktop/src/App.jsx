import React, { useState, useEffect, useMemo, useRef } from 'react';
import { 
  Home, 
  CalendarCheck, 
  CalendarDays, 
  Clock, 
  MoreHorizontal, 
  Check, 
  Plus, 
  RotateCw, 
  Pin, 
  PinOff, 
  User, 
  Sparkles, 
  MoreVertical,
  ChevronDown, 
  ChevronUp, 
  Download
} from 'lucide-react';
import { syncApi } from './services/api';
import { shouldApplyRemote, isAppVisible, appDisplayName } from './services/sync-merge';
import DateTimePicker from './DateTimePicker';
import { eventDeadline } from './services/event-deadline';
import TimeNumberInput from './TimeNumberInput';

const DEFAULT_TASKS = [];
const DEMO_TASKS = [
  ['com.hoyoverse.nap', 'Daily', 'zzz_coffee'],
  ['com.hoyoverse.nap', 'Daily', 'zzz_scratch'],
  ['com.hoyoverse.nap', 'Daily', 'zzz_stamina'],
  ['com.hoyoverse.nap', 'Weekly', 'zzz_hollow'],
  ['com.hoyoverse.nap', 'Weekly', 'zzz_notorious'],
  ['com.hoyoverse.hkrpg', 'Daily', 'hsr_daily'],
  ['com.hoyoverse.hkrpg', 'Daily', 'hsr_stamina'],
  ['com.hoyoverse.hkrpg', 'Weekly', 'hsr_simulated'],
  ['com.hoyoverse.hkrpg', 'Weekly', 'hsr_echo'],
  ['personal', 'Personal', 'personal_water'],
];
const isDemoTask = task => DEMO_TASKS.some(([pkg, category, id]) =>
  task.packageName === pkg && task.category === category && task.taskId === id);

export default function App() {
  const [tasks, setTasks] = useState(() => {
    const saved = localStorage.getItem('daily_check_tasks');
    return saved ? JSON.parse(saved).filter(task => !isDemoTask(task)) : DEFAULT_TASKS;
  });

  const [sortMode, setSortMode] = useState(() => localStorage.getItem('desktop_sort_mode') || 'name');
  const [desktopStatus, setDesktopStatus] = useState('바탕화면 연결 확인 중');
  const [currentNav, setCurrentNav] = useState('home'); // 'home' | 'Daily' | 'Weekly' | 'Event' | 'more'
  const [expandedGames, setExpandedGames] = useState({
    'com.hoyoverse.nap': true,
    'com.hoyoverse.hkrpg': true,
    'personal': true,
  });

  const [isPositionLocked, setIsPositionLocked] = useState(true);
  const [widgetOpacity, setWidgetOpacity] = useState(() => Number(localStorage.getItem('widget_opacity') || 90));
  const [appSettings, setAppSettings] = useState(() => JSON.parse(localStorage.getItem('daily_check_settings') || '[]'));
  const [pendingSettings, setPendingSettings] = useState(() => JSON.parse(localStorage.getItem('pending_app_settings') || '[]'));
  const [auth, setAuth] = useState(() => syncApi.getStoredAuth());
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncToast, setSyncToast] = useState('');
  const [actionTask, setActionTask] = useState(null);
  const [editingTask, setEditingTask] = useState(null);
  const [editTitle, setEditTitle] = useState('');
  const [editTime, setEditTime] = useState('');
  const [originalEditTime, setOriginalEditTime] = useState('');
  const [editDays, setEditDays] = useState(0);
  const [editHours, setEditHours] = useState(0);
  const [originalEditHours, setOriginalEditHours] = useState(0);
  const [showAddModal, setShowAddModal] = useState(false);
  const [newTaskTitle, setNewTaskTitle] = useState('');
  const [newTaskTime, setNewTaskTime] = useState('');
  const [deadlineNow, setDeadlineNow] = useState(Date.now);
  const [addResetMinute, setAddResetMinute] = useState(240);
  const [addResetDay, setAddResetDay] = useState(2);
  const [addDays, setAddDays] = useState(0);
  const [addHours, setAddHours] = useState(0);
  const [addGame, setAddGame] = useState('com.hoyoverse.nap');
  const [addCategory, setAddCategory] = useState('Daily');
  const [pairingInput, setPairingInput] = useState('');
  const [isPairing, setIsPairing] = useState(false);
  const needsKeyboard = showAddModal || !!editingTask || currentNav === 'more';

  useEffect(() => {
    if (!window.__TAURI__) return;
    let disposed = false;
    const updateInputMode = async () => {
      await window.__TAURI__.core.invoke('desktop_edit_mode', { editing: !isPositionLocked || needsKeyboard });
      if (needsKeyboard && !disposed) {
        await window.__TAURI__.window.getCurrentWindow().setFocus();
        document.querySelector('input[autofocus], input[type="text"]')?.focus();
      }
    };
    updateInputMode().catch(console.error);
    return () => { disposed = true; };
  }, [needsKeyboard, isPositionLocked]);

  // Persist tasks
  useEffect(() => {
    localStorage.setItem('daily_check_tasks', JSON.stringify(tasks));
  }, [tasks]);
  useEffect(() => {
    let timer;
    const refresh = () => {
      const now = Date.now();
      setDeadlineNow(now);
      const next = Math.min(...tasks.filter(task => !task.isDeleted && task.deadline > now).map(task => task.deadline));
      if (Number.isFinite(next)) timer = setTimeout(refresh, Math.min(next - now, 2147483647));
    };
    refresh();
    return () => clearTimeout(timer);
  }, [tasks]);
  useEffect(() => {
    localStorage.setItem('pending_app_settings', JSON.stringify(pendingSettings));
    localStorage.setItem('daily_check_settings', JSON.stringify(appSettings));
  }, [pendingSettings, appSettings]);

  useEffect(() => {
    if (!window.__TAURI__?.window) return;
    const win = window.__TAURI__.window.getCurrentWindow();
    win.setResizable(false).catch(console.error);
    let disposed = false;
    const listeners = [];
    const setup = async () => {
      const unstatus = await window.__TAURI__.event.listen('desktop-status', event => setDesktopStatus(event.payload));
      if (disposed) unstatus(); else listeners.push(unstatus);
      const saved = JSON.parse(localStorage.getItem('widget_bounds') || 'null');
      if (saved) {
        const monitors = await window.__TAURI__.window.availableMonitors();
        if (monitors.some(m => saved.x >= m.position.x && saved.y >= m.position.y && saved.x < m.position.x + m.size.width - 40 && saved.y < m.position.y + m.size.height - 40)) {
          await window.__TAURI__.core.invoke('desktop_set_position', { x: saved.x, y: saved.y });
        }
      }
      if (saved?.width && saved?.height) await win.setSize(new window.__TAURI__.dpi.PhysicalSize(saved.width, saved.height));
      const unresize = await win.onResized(({ payload }) => localStorage.setItem('widget_bounds', JSON.stringify({ ...JSON.parse(localStorage.getItem('widget_bounds') || '{}'), ...payload })));
      if (disposed) unresize(); else listeners.push(unresize);
      const unlisten = await win.onMoved(async () => {
        const [x, y] = await window.__TAURI__.core.invoke('desktop_position');
        const payload = { x, y };
        localStorage.setItem('widget_bounds', JSON.stringify({ ...JSON.parse(localStorage.getItem('widget_bounds') || '{}'), ...payload }));
      });
      if (disposed) unlisten(); else listeners.push(unlisten);
    };
    setup().catch(console.error);
    return () => { disposed = true; listeners.forEach(fn => fn()); };
  }, []);

  const togglePositionLock = async () => {
    const locked = !isPositionLocked;
    try {
      if (window.__TAURI__) {
        await window.__TAURI__.core.invoke('desktop_edit_mode', { editing: !locked || needsKeyboard });
        await window.__TAURI__.window.getCurrentWindow().setResizable(!locked);
      }
      setIsPositionLocked(locked);
    } catch (error) { showToast(String(error)); }

  };

  const showToast = (msg) => {
    setSyncToast(msg);
    setTimeout(() => setSyncToast(''), 2500);
  };

  const syncRunning = useRef(false);
  const syncQueued = useRef(false);
  const syncDelay = useRef(30_000);
  const handleSync = async (initial = false, silent = false) => {
    if (syncRunning.current) { syncQueued.current = true; return; }
    if (!syncApi.getStoredAuth()?.token) {
      setCurrentNav('more');
      showToast('모바일 앱과 연동해 주세요');
      return;
    }
    syncRunning.current = true;
    setIsSyncing(true);
    try {

      const account = syncApi.getStoredAuth().user?.id || 'current';
      const baselineKey = `sync_sent_v1_${account}`;
      const cleanupKey = `demo_deleted_v1_${account}`;
      const baseline = JSON.parse(localStorage.getItem(baselineKey) || '{}');
      const taskKey = task => `${task.packageName}/${task.category}/${task.taskId}`;
      const sentTasks = initial === true ? [] : tasks.filter(task => !isDemoTask(task) && baseline[taskKey(task)] !== task.updatedAt);
      const res = await syncApi.sync({
        tasks: [
          ...sentTasks,
          ...(localStorage.getItem(cleanupKey) ? [] : DEMO_TASKS.map(([packageName, category, taskId]) => ({
            packageName, category, taskId, title: '', isDeleted: true, updatedAt: Date.now(),
          }))),
        ],
        settings: pendingSettings.map(({ isInstalled, ...setting }) => setting),
        since: 0,
      });

      const receivedSettings = res.serverChanges?.settings || [];
      setAppSettings(previous => {
        const merged = new Map(receivedSettings.map(setting => [setting.packageName, setting]));
        for (const setting of previous) {
          if (setting.updatedAt > (merged.get(setting.packageName)?.updatedAt || 0)) merged.set(setting.packageName, { ...setting, isInstalled: merged.get(setting.packageName)?.isInstalled ?? setting.isInstalled });
        }
        return [...merged.values()];
      });
      setPendingSettings(previous => previous.filter(setting => !pendingSettings.some(sent => sent.packageName === setting.packageName && sent.updatedAt === setting.updatedAt)));
      const serverTasks = res.serverChanges?.tasks || [];
      const changed = sentTasks.length > 0 || serverTasks.some(task => baseline[taskKey(task)] !== task.updatedAt) || JSON.stringify(receivedSettings) !== localStorage.getItem('adaptive_settings');
      syncDelay.current = changed ? 30_000 : Math.min(syncDelay.current * 2, 300_000);
      localStorage.setItem('adaptive_settings', JSON.stringify(receivedSettings));
      for (const task of [...sentTasks, ...serverTasks]) baseline[taskKey(task)] = task.updatedAt;
      localStorage.setItem(baselineKey, JSON.stringify(baseline));
      localStorage.setItem(cleanupKey, 'true');
      if (serverTasks.length > 0) {
        setTasks(prev => {
          const map = new Map((initial === true ? [] : prev).map(t => [`${t.packageName}_${t.category}_${t.taskId}`, t]));
          for (const st of serverTasks) {
            const key = `${st.packageName}_${st.category}_${st.taskId}`;
            if (!shouldApplyRemote(map.get(key), st)) continue;
            if (st.isDeleted || isDemoTask(st) || (st.category === 'Weekly' && st.taskId === '__app_completion__')) {
              map.delete(key);
            } else {
              const gameName = receivedSettings.find(s => s.packageName === st.packageName)?.appName || (st.packageName.includes('nap') ? '젠레스 존 제로' 
                : st.packageName.includes('hkrpg') ? '붕괴: 스타레일' 
                : st.packageName === 'personal' ? '개인 계획' : st.packageName);
              map.set(key, { ...st, gameName });
            }
          }
          return Array.from(map.values());
        });
      }

      localStorage.setItem('daily_check_last_sync', String(res.syncedAt || Date.now()));
      if (!silent) showToast('동기화 완료');
    } catch (err) {
      syncDelay.current = Math.min(syncDelay.current * 2, 300_000);
      if (!silent) showToast(err.message || '동기화 실패');
    } finally {
      syncRunning.current = false;
      if (syncQueued.current) {
        syncQueued.current = false;
        setTimeout(() => autoSync.current(), 0);
      }
      setIsSyncing(false);
    }
  };

  const autoSync = useRef(null);
  useEffect(() => {
    autoSync.current = () => {
      if (syncApi.getStoredAuth()?.token) return handleSync(false, true);
    };
  });
  useEffect(() => {
    let disposed = false;
    let timer;
    const poll = async () => {
      await autoSync.current();
      if (!disposed) timer = setTimeout(poll, syncDelay.current);
    };
    poll();
    const onFocus = () => { syncDelay.current = 30_000; autoSync.current(); };
    window.addEventListener('focus', onFocus);
    return () => { disposed = true; clearTimeout(timer); window.removeEventListener('focus', onFocus); };
  }, []);
  const syncFingerprint = JSON.stringify([tasks, pendingSettings]);
  useEffect(() => {
    const timer = setTimeout(() => autoSync.current(), 1500);
    return () => clearTimeout(timer);
  }, [syncFingerprint, auth]);

  const [homeCompleted, setHomeCompleted] = useState({});
  useEffect(() => {
    const expiry = Math.min(...Object.values(homeCompleted).map(entry => entry.until));
    if (!Number.isFinite(expiry)) return;
    const timer = setTimeout(() => {
      const now = Date.now();
      setHomeCompleted(previous => Object.fromEntries(Object.entries(previous).filter(([, entry]) => entry.until > now)));
    }, Math.max(0, expiry - Date.now()));
    return () => clearTimeout(timer);
  }, [homeCompleted]);

  const toggleTask = (task) => {
    {
      const key = `${task.packageName}/${task.category}/${task.taskId}`;
      setHomeCompleted(previous => {
        const next = { ...previous };
        next[key] = { until: Date.now() + 5 * 60 * 1000, rank: previous[key]?.rank ?? (task.done ? 1 : 0) };
        return next;
      });
    }
    const now = Date.now();
    setTasks(prev => prev.map(t => {
      if (t.packageName === task.packageName && t.category === task.category && t.taskId === task.taskId) {
        return { ...t, done: !t.done, updatedAt: now };
      }
      return t;
    }));
  };

  const startEdit = (task, event) => {
    event.stopPropagation();
    const date = new Date(task.deadline || 0);
    const time = task.deadline > 0 ? new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16) : '';
    setEditingTask(task);
    setEditTitle(task.title);
    setEditTime(time);
    setOriginalEditTime(time);
    const remainingHours = Math.floor(Math.max(0, (task.deadline || 0) - Date.now()) / 3600000);
    setEditDays(Math.floor(remainingHours / 24));
    setEditHours(remainingHours % 24);
    setOriginalEditHours(remainingHours);
  };
  const saveEdit = event => {
    event.preventDefault();
    if (!editTitle.trim() || !editingTask) return;
    let deadline = editingTask.deadline;
    if (editingTask.category === 'Event' && editDays * 24 + editHours !== originalEditHours) {
      if (editDays * 24 + editHours <= 0) return;
      const setting = appSettings.find(item => item.packageName === editingTask.packageName);
      const resetMinute = editingTask.resetMinute != null && editingTask.resetMinute >= 0
        ? editingTask.resetMinute : setting?.dailyResetMinute ?? 240;
      deadline = eventDeadline(Date.now(), editDays, editHours, resetMinute, editingTask.resetMinute != null && editingTask.resetMinute >= 0);
    } else if (editingTask.category === 'Personal' && editTime !== originalEditTime) {
      const date = editTime ? new Date(editTime) : null;
      if (date && !Number.isFinite(date.getTime())) return;
      if (date && editingTask.category !== 'Personal' && editingTask.resetMinute >= 0) date.setHours(Math.floor(editingTask.resetMinute / 60), editingTask.resetMinute % 60, 0, 0);
      deadline = date?.getTime() || 0;
    }
    setTasks(previous => previous.map(task => task.packageName === editingTask.packageName && task.category === editingTask.category && task.taskId === editingTask.taskId
      ? { ...task, title: editTitle.trim(), deadline, updatedAt: Date.now() } : task));
    setEditingTask(null);
  };
  const deleteTask = (task, e) => {
    e.stopPropagation();
    const now = Date.now();
    setTasks(prev => prev.map(t => {
      if (t.packageName === task.packageName && t.category === task.category && t.taskId === task.taskId) {
        return { ...t, isDeleted: true, updatedAt: now };
      }
      return t;
    }));
  };

  const handleAddTask = (e) => {
    e.preventDefault();
    if (!newTaskTitle.trim()) return;
    const deadline = addCategory === 'Personal' && newTaskTime ? new Date(newTaskTime).getTime()
      : addCategory === 'Event' && (addDays || addHours) ? eventDeadline(Date.now(), addDays, addHours, addResetMinute) : 0;
    if (!Number.isFinite(deadline)) return;
    if (addCategory === 'Daily' || addCategory === 'Weekly') {
      const setting = { dailyResetMinute: 240, weeklyResetMinute: 240, weeklyResetDay: 2,
        ...appSettings.find(item => item.packageName === addGame), packageName: addGame, appName: appName(addGame), updatedAt: Date.now(),
        ...(addCategory === 'Daily' ? { dailyResetMinute: addResetMinute } : { weeklyResetMinute: addResetMinute, weeklyResetDay: addResetDay }) };
      setAppSettings(previous => [...previous.filter(item => item.packageName !== addGame), setting]);
      setPendingSettings(previous => [...previous.filter(item => item.packageName !== addGame), setting]);
    }
    const gameName = addGame === 'personal' ? '개인 계획' : appName(addGame);

    const newTask = {
      packageName: addGame,
      gameName,
      category: addCategory,
      taskId: 'task_' + Date.now().toString(36),
      title: newTaskTitle.trim(),
      done: false,
      deadline,
      routineDays: 0,
      resetMinute: -1,
      updatedAt: Date.now(),
    };

    setTasks(prev => [newTask, ...prev]);
    setNewTaskTitle('');
    setNewTaskTime('');
    setShowAddModal(false);
    showToast('숙제 추가됨');
  };

  const toggleGameExpanded = (pkg) => {
    setExpandedGames(prev => ({ ...prev, [pkg]: !prev[pkg] }));
  };
  const loadAddReset = (pkg, category) => {
    const setting = appSettings.find(item => item.packageName === pkg);
    setAddResetMinute((category === 'Weekly' ? setting?.weeklyResetMinute : setting?.dailyResetMinute) ?? 240);
    setAddResetDay(setting?.weeklyResetDay ?? 2);
  };

  // Import online events
  const handleImportEvents = async () => {
    try {
      showToast('이벤트 수집 중...');
      const data = await syncApi.fetchEvents();
      if (!data.events || data.events.length === 0) {
        showToast('가져올 이벤트가 없습니다');
        return;
      }
      const newItems = data.events.slice(0, 5).map(evt => ({
        packageName: evt.game === 'zzz' ? 'com.hoyoverse.nap' : 'com.hoyoverse.hkrpg',
        gameName: evt.game === 'zzz' ? '젠레스 존 제로' : '붕괴: 스타레일',
        category: 'Event',
        taskId: evt.id,
        title: evt.title,
        done: false,
        deadline: Date.parse(evt.endAt) || 0,
        routineDays: 0,
        resetMinute: 240,
        updatedAt: Date.now(),
      }));

      setTasks(prev => {
        const existing = new Set(prev.map(t => t.taskId));
        const filtered = newItems.filter(i => !existing.has(i.taskId));
        return [...filtered, ...prev];
      });
      showToast(`${newItems.length}개 이벤트 가져옴`);
    } catch {
      showToast('이벤트 가져오기 실패');
    }
  };

  // Calculations for Home Dashboard
  const visibleTasks = useMemo(() => tasks.filter(task => isAppVisible(task.packageName, appSettings)), [tasks, appSettings]);
  const dailyTasks = useMemo(() => visibleTasks.filter(t => t.category === 'Daily' && !t.isDeleted), [visibleTasks]);
  const dailyDone = useMemo(() => dailyTasks.filter(t => t.done).length, [dailyTasks]);
  const dailyTotal = dailyTasks.length;
  const progressPercent = dailyTotal > 0 ? Math.round((dailyDone / dailyTotal) * 100) : 0;
  const pendingTasks = useMemo(() => visibleTasks.filter(t => !t.isDeleted && (!t.done || homeCompleted[`${t.packageName}/${t.category}/${t.taskId}`])), [visibleTasks, homeCompleted]);
  const eventTasks = useMemo(() => visibleTasks.filter(t => t.category === 'Event' && !t.isDeleted), [visibleTasks]);

  const appName = pkg => appDisplayName(pkg, appSettings, tasks);
  const registeredApps = [...new Set([
    ...appSettings.map(setting => setting.packageName),
    ...visibleTasks.filter(task => !task.isDeleted).map(task => task.packageName),
  ])].filter(pkg => pkg !== 'personal' && isAppVisible(pkg, appSettings) && !/^[a-z][\w]*(?:\.[\w]+)+$/i.test(appName(pkg).trim())).sort((a, b) => appName(a).localeCompare(appName(b), 'ko'));
  const taskTime = task => task.deadline > 0 ? task.deadline : Infinity;
  const deadlineLabel = task => task.deadline <= 0 ? '시간 미설정' : task.deadline <= deadlineNow ? '마감'
    : new Intl.DateTimeFormat('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(task.deadline)) + '까지';
  const completedRank = task => homeCompleted[`${task.packageName}/${task.category}/${task.taskId}`]?.rank ?? (task.done ? 1 : 0);
  const compareTasks = (a, b) => completedRank(a) !== completedRank(b)
    ? completedRank(a) - completedRank(b)
    : sortMode === 'name'
    ? a.title.localeCompare(b.title, 'ko')
    : (taskTime(a) === taskTime(b) ? a.title.localeCompare(b.title, 'ko') : taskTime(a) - taskTime(b));
  const packageTime = pkg => {
    const setting = appSettings.find(s => s.packageName === pkg);
    if (currentNav === 'Daily' || currentNav === 'Weekly') {
      const minute = currentNav === 'Weekly' ? setting?.weeklyResetMinute : setting?.dailyResetMinute;
      return minute != null && minute >= 0 ? minute : Infinity;
    }
    return Math.min(...visibleTasks.filter(t => t.packageName === pkg && t.category === currentNav && !t.isDeleted).map(taskTime));
  };
  const sortedPackages = [...new Set(visibleTasks.filter(t => !t.isDeleted).map(t => t.packageName))].sort((a, b) => {
    if (sortMode === 'name' || packageTime(a) === packageTime(b)) return appName(a).localeCompare(appName(b), 'ko');
    return packageTime(a) - packageTime(b);
  });
  const todayDateStr = useMemo(() => {
    const d = new Date();
    const days = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'];
    return `${d.getMonth() + 1}월 ${d.getDate()}일 ${days[d.getDay()]}`;
  }, []);

  return (
    <div style={{ opacity: widgetOpacity / 100 }} className="w-screen h-screen bg-[#0d1117] text-[#f0f6fc] rounded-[22px] flex flex-col border border-[#30363d] shadow-2xl overflow-hidden font-sans select-none relative">
      
      {/* 1. Mobile Status Bar & Window Header (Draggable) */}
      <div 
        data-tauri-drag-region={isPositionLocked ? undefined : true} 
        className="h-10 px-4 bg-[#161b22] border-b border-[#30363d] flex items-center justify-between shrink-0"
      >
        <div className="flex items-center gap-2 pointer-events-none">
          <div className="w-2 h-2 rounded-full bg-[#2f81f7]" />
          <span className="font-semibold text-xs tracking-tight text-[#f0f6fc]">Daily Check</span>
        </div>

        <div className="flex items-center gap-1.5">
          <button 
            onClick={togglePositionLock}
            title={isPositionLocked ? "위치·크기 조정 허용" : "위치·크기 고정"}
            aria-label={isPositionLocked ? '위치·크기 조정 허용' : '위치·크기 고정'}
            aria-pressed={!isPositionLocked}
            className={`p-1 rounded-md transition-colors ${!isPositionLocked ? 'text-[#58a6ff] bg-[#1c2d4f]' : 'text-[#8b949e] hover:text-[#f0f6fc] hover:bg-[#21262d]'}`}
          >
            {!isPositionLocked ? <Pin className="w-3.5 h-3.5" /> : <PinOff className="w-3.5 h-3.5" />}
          </button>

          <button 
            onClick={handleSync}
            disabled={isSyncing || isPositionLocked}
            title="동기화"
            className="p-1 rounded-md text-[#8b949e] enabled:hover:text-[#f0f6fc] enabled:hover:bg-[#21262d] disabled:opacity-40 disabled:cursor-default transition-colors"
          >
            <RotateCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin text-[#2f81f7]' : ''}`} />
          </button>
        </div>
      </div>

      {/* Toast Notification */}
      {syncToast && (
        <div className="absolute top-12 left-1/2 -translate-x-1/2 z-50 px-3 py-1 bg-[#1c2d4f] border border-[#2f81f7]/50 text-[#58a6ff] text-[12px] font-medium rounded-full shadow-lg pointer-events-none animate-bounce">
          {syncToast}
        </div>
      )}

      {/* 2. Main Scrollable Screen Content */}
      <div className="flex-1 overflow-y-auto">
        
        {/* ===================== TAB 1: HOME ===================== */}
        {currentNav === 'home' && (
          <div className="p-4 space-y-5 animate-fade-in">
            {/* Header */}
            <div className="pt-1">
              <h1 className="text-xl font-bold text-[#f0f6fc]">오늘의 할 일 목록</h1>
              <p className="text-xs text-[#8b949e] mt-0.5">{todayDateStr}</p>
            </div>

            {/* Material 3 Progress Card (Blue Container) */}
            <div className="p-5 rounded-2xl bg-[#1c2d4f] border border-[#2f81f7]/30 text-[#f0f6fc] shadow-sm">
              <span className="text-xs font-semibold text-[#58a6ff]">데일리 진행 상황</span>
              <div className="flex items-baseline justify-between mt-2">
                <span className="text-[44px] font-bold tracking-tight">{progressPercent}%</span>
                <span className="text-xs font-medium text-[#c9d1d9]">{dailyDone}/{dailyTotal} 완료</span>
              </div>

              {/* Linear Progress Bar */}
              <div className="w-full h-2 bg-[#0d1117]/60 rounded-full mt-3.5 overflow-hidden">
                <div 
                  className="h-full bg-[#58a6ff] transition-all duration-500 rounded-full"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>

              <p className="text-[12px] text-[#58a6ff] mt-3 font-medium">
                {progressPercent === 100 
                  ? '오늘의 모든 숙제를 마쳤습니다! 🎉' 
                  : `${dailyTotal - dailyDone}개의 일일 숙제가 남아있습니다.`}
              </p>
            </div>

            {/* Soon-ending Events Section */}
            {eventTasks.length > 0 && (
              <div>
                <h2 className="text-xs font-bold text-[#f0f6fc] uppercase tracking-wider mb-2 flex items-center gap-1.5">
                  <Clock className="w-3.5 h-3.5 text-[#58a6ff]" /> 곧 끝나는 이벤트
                </h2>
                <div className="space-y-1.5">
                  {eventTasks.slice(0, 2).map(task => (
                    <div 
                      key={task.taskId}
                      onClick={() => toggleTask(task)}
                      className="p-3 rounded-xl bg-[#161b22] border border-[#30363d] flex items-center justify-between cursor-pointer hover:border-[#58a6ff]/40 transition-colors"
                    >
                      <div className="flex items-center gap-2.5 min-w-0">
                        {appSettings.find(s => s.packageName === task.packageName)?.appIcon ? (
                          <img src={appSettings.find(s => s.packageName === task.packageName).appIcon} alt="" className="w-8 h-8 rounded-lg object-contain shrink-0" />
                        ) : (
                          <div className="w-8 h-8 rounded-lg bg-[#21262d] flex items-center justify-center shrink-0 text-[#58a6ff]">
                            {task.packageName === 'personal' ? <CalendarDays className="w-4 h-4" /> : <CalendarCheck className="w-4 h-4" />}
                          </div>
                        )}
                        <div className={`w-4 h-4 rounded-md flex items-center justify-center shrink-0 ${
                          task.done ? 'bg-[#2f81f7] text-white' : 'border border-[#8b949e]'
                        }`}>
                          {task.done && <Check className="w-3 h-3 stroke-[3]" />}
                        </div>
                        <div className="min-w-0">
                          <p className={`text-xs font-medium truncate ${task.done ? 'line-through text-[#8b949e]' : 'text-[#f0f6fc]'}`}>
                            {task.title}
                          </p>
                          <p className="text-[12px] text-[#8b949e]">{task.gameName}</p>
                        </div>
                      </div>
                      <span className="text-[12px] px-2 py-0.5 rounded-full bg-[#21262d] text-[#58a6ff] font-sans shrink-0">
                        {deadlineLabel(task)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Pending Tasks Section */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <h2 className="text-xs font-bold text-[#f0f6fc] uppercase tracking-wider">아직 안 끝낸 일</h2>
                <span className="text-[12px] text-[#8b949e]">{visibleTasks.filter(t => !t.done && !t.isDeleted).length}개 남음</span>
              </div>

              {pendingTasks.length === 0 ? (
                <div className="p-6 rounded-xl bg-[#161b22] border border-[#30363d] text-center text-[#8b949e] text-xs">
                  남은 할 일이 없습니다. 수고하셨습니다! ✨
                </div>
              ) : (
                <div className="space-y-1.5">
                  {pendingTasks.slice(0, 4).map(task => (
                    <div
                      key={`${task.packageName}_${task.taskId}`}
                      onClick={() => toggleTask(task)}
                      className="p-2.5 rounded-xl bg-[#161b22] border border-[#30363d] flex items-center justify-between cursor-pointer hover:border-[#2f81f7]/50 hover:bg-[#21262d] transition-all"
                    >
                      <div className="flex items-center gap-2.5 min-w-0">
                        {appSettings.find(s => s.packageName === task.packageName)?.appIcon ? (
                          <img src={appSettings.find(s => s.packageName === task.packageName).appIcon} alt="" className="w-8 h-8 rounded-lg object-contain shrink-0" />
                        ) : (
                          <div className="w-8 h-8 rounded-lg bg-[#21262d] flex items-center justify-center shrink-0 text-[#58a6ff]">
                            {task.packageName === 'personal' ? <CalendarDays className="w-4 h-4" /> : <CalendarCheck className="w-4 h-4" />}
                          </div>
                        )}
                        <div className={`w-4 h-4 rounded-md flex items-center justify-center shrink-0 ${task.done ? 'bg-[#2f81f7] text-white' : 'border border-[#8b949e]'}`}>
                          {task.done && <Check className="w-3 h-3 stroke-[3]" />}
                        </div>
                        <div className="min-w-0">
                          <p className={`text-xs font-medium truncate ${task.done ? 'line-through text-[#8b949e]' : 'text-[#f0f6fc]'}`}>{task.title}</p>
                          <p className="text-[12px] text-[#8b949e]">{task.gameName} · {task.category}</p>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <button
                onClick={() => setCurrentNav('Daily')}
                className="w-full mt-3 py-2.5 rounded-xl bg-[#21262d] hover:bg-[#30363d] text-[#58a6ff] text-xs font-medium transition-colors border border-[#30363d]"
              >
                데일리 목록 바로가기 →
              </button>
            </div>
          </div>
        )}

        {/* ===================== TAB 2, 3, 4: LISTS (Daily, Weekly, Event) ===================== */}
        {(currentNav === 'Daily' || currentNav === 'Weekly' || currentNav === 'Event' || currentNav === 'Personal') && (
          <div className="p-4 space-y-4 animate-fade-in pb-16">
            {/* Title Bar */}
            <div className="flex items-center justify-between">
              <div>
                <h1 className="text-lg font-bold text-[#f0f6fc]">
                  {currentNav === 'Daily' ? '데일리 숙제' : currentNav === 'Weekly' ? '위클리 숙제' : currentNav === 'Personal' ? '일정' : '이벤트 숙제'}
                </h1>
                <p className="text-[12px] text-[#8b949e]">
                  {visibleTasks.filter(t => t.category === currentNav && !t.isDeleted).length}개의 등록된 항목
                </p>
              </div>

              {currentNav === 'Event' && (
                <button
                  onClick={handleImportEvents}
                  className="px-2.5 py-1 rounded-lg bg-[#21262d] hover:bg-[#30363d] text-[#58a6ff] text-xs font-medium border border-[#30363d] flex items-center gap-1"
                >
                  <Download className="w-3 h-3" /> 가져오기
                </button>
              )}
            </div>

            <label className="flex items-center justify-end gap-2 text-[12px] text-[#8b949e]">
              정렬
              <select aria-label="정렬 방법" value={sortMode}
                onChange={event => { setSortMode(event.target.value); localStorage.setItem('desktop_sort_mode', event.target.value); }}
                className="rounded-lg border border-[#30363d] bg-[#161b22] px-2 py-1 text-[#f0f6fc]">
                <option value="name">가나다 순</option>
                <option value="time">시간 순</option>
              </select>
            </label>
            {sortedPackages.map(pkg => {
              const gameTasks = visibleTasks.filter(t => t.packageName === pkg && t.category === currentNav && !t.isDeleted).sort(compareTasks);
              if (gameTasks.length === 0) return null;

              const gameName = appName(pkg);
              const doneCount = gameTasks.filter(t => t.done).length;
              const allDone = doneCount === gameTasks.length;
              const isExpanded = expandedGames[pkg] !== false;

              return (
                <div key={pkg} className={currentNav === 'Personal' ? '' : 'rounded-2xl bg-[#161b22] border border-[#30363d] overflow-hidden'}>
                  {/* Game Card Header */}
                  {currentNav !== 'Personal' && <div 
                    onClick={() => toggleGameExpanded(pkg)}
                    className="p-3.5 flex items-center justify-between cursor-pointer hover:bg-[#21262d]/50 transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-xl bg-[#21262d] flex items-center justify-center font-bold text-xs text-[#58a6ff] border border-[#30363d]">
                        {appSettings.find(s => s.packageName === pkg)?.appIcon
                          ? <img src={appSettings.find(s => s.packageName === pkg).appIcon} alt="" className="w-8 h-8 rounded-lg object-contain" />
                          : gameName.slice(0, 1)}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-[#f0f6fc]">{gameName}</span>
                          {allDone && (
                            <span className="text-[12px] px-1.5 py-0.5 bg-[#2f81f7]/20 text-[#58a6ff] rounded font-medium">
                              완료 ✓
                            </span>
                          )}
                        </div>
                        <p className="text-[12px] text-[#8b949e] mt-0.5">
                          {pkg === 'personal' ? '나의 계획' : (() => { const setting = appSettings.find(s => s.packageName === pkg); const minute = currentNav === 'Weekly' ? setting?.weeklyResetMinute : setting?.dailyResetMinute; return minute == null ? '초기화 미설정' : '초기화 ' + String(Math.floor(minute / 60)).padStart(2, '0') + ':' + String(minute % 60).padStart(2, '0'); })()} · {doneCount}/{gameTasks.length} 완료
                        </p>
                      </div>
                    </div>

                    <div className="text-[#8b949e]">
                      {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </div>
                  </div>}

                  {/* Tasks under this Game */}
                  {(currentNav === 'Personal' || isExpanded) && (
                    <div className={currentNav === 'Personal' ? 'space-y-2' : 'border-t border-[#30363d] divide-y divide-[#30363d]/60 bg-[#0d1117]/40'}>
                      {gameTasks.map(task => (
                        <div
                          key={task.taskId}
                          onClick={() => toggleTask(task)}
                          className={`px-4 py-2.5 flex items-center justify-between group cursor-pointer hover:bg-[#21262d]/40 transition-colors ${currentNav === 'Personal' ? 'rounded-2xl bg-[#161b22] border border-[#30363d]' : ''}`}
                        >
                          <div className="flex items-center gap-3 min-w-0 pr-2">
                            <div className={`w-4 h-4 rounded flex items-center justify-center shrink-0 transition-colors ${
                              task.done ? 'bg-[#2f81f7] text-white' : 'border border-[#8b949e] group-hover:border-[#58a6ff]'
                            }`}>
                              {task.done && <Check className="w-3 h-3 stroke-[3]" />}
                            </div>
                            <div className="min-w-0">
                            <span className={`block text-xs truncate ${
                              task.done ? 'line-through text-[#8b949e]' : 'text-[#f0f6fc]'
                            }`}>
                              {task.title}
                            </span>
                            {(currentNav === 'Personal' || currentNav === 'Event') && (
                              <span className="block mt-1 text-[12px] text-[#8b949e]">
                                {deadlineLabel(task)}
                              </span>
                            )}
                            </div>
                          </div>

                          <button title="항목 메뉴" aria-label={`${task.title} 메뉴`} aria-haspopup="menu"
                            onClick={event => { event.stopPropagation(); setActionTask({ task, top: Math.max(8, Math.min(event.clientY, window.innerHeight - 130)) }); }}
                            className="p-2 shrink-0 rounded-lg text-[#8b949e] hover:bg-[#21262d] hover:text-[#f0f6fc]">
                            <MoreVertical className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}

            {visibleTasks.filter(t => t.category === currentNav && !t.isDeleted).length === 0 && (
              <div className="p-8 rounded-2xl bg-[#161b22] border border-[#30363d] text-center text-[#8b949e] text-xs space-y-2">
                <Sparkles className="w-6 h-6 mx-auto text-[#58a6ff] opacity-60" />
                <p>등록된 숙제가 없습니다.</p>
              </div>
            )}
          </div>
        )}

        {/* ===================== TAB 5: MORE ===================== */}
        {currentNav === 'more' && (
          <div className="p-4 space-y-4 animate-fade-in">
            <h1 className="text-lg font-bold text-[#f0f6fc]">더보기 & 설정</h1>

            {/* Mobile Pairing Card */}
            <div className="p-4 rounded-2xl bg-[#161b22] border border-[#30363d] space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-[#f0f6fc] flex items-center gap-1.5">
                  <User className="w-4 h-4 text-[#58a6ff]" /> 스마트폰 앱과 실시간 연동
                </span>
                <span className={`text-[12px] px-2 py-0.5 rounded-full font-medium ${
                  auth ? 'bg-emerald-500/20 text-emerald-400' : 'bg-[#21262d] text-[#8b949e]'
                }`}>
                  {auth ? '연동됨' : '미연동'}
                </span>
              </div>

              {auth ? (
                <div className="space-y-3 pt-1">
                  <div className="p-2.5 rounded-xl bg-[#21262d] border border-[#30363d]">
                    <p className="text-[12px] text-[#8b949e]">연동 상태</p>
                    <p className="text-xs font-semibold text-emerald-400 mt-0.5">스마트폰과 실시간 연결됨 ✓</p>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={handleSync}
                      disabled={isSyncing || isPositionLocked}
                      className="flex-1 py-2 rounded-xl bg-[#2f81f7] hover:bg-[#1f6feb] text-white text-xs font-semibold transition-colors flex items-center justify-center gap-1.5"
                    >
                      <RotateCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin' : ''}`} /> 지금 동기화
                    </button>
                    <button
                      onClick={() => {
                        syncApi.setStoredAuth(null);
                        setAuth(null);
                        showToast('연동 해제됨');
                      }}
                      className="px-3 py-2 rounded-xl bg-[#21262d] hover:bg-[#30363d] text-[#f85149] text-xs font-medium border border-[#30363d]"
                    >
                      해제
                    </button>
                  </div>
                </div>
              ) : (
                <div className="space-y-2.5 pt-1">
                  <p className="text-[12px] text-[#8b949e] leading-relaxed">
                    스마트폰 앱의 <strong className="text-[#f0f6fc]">[더보기 ➔ 연동 코드]</strong>를 누르고 표시된 6자리 번호를 입력하세요.
                  </p>
                  <input
                    type="text"
                    maxLength={7}
                    placeholder="예: 842-195"
                    value={pairingInput}
                    onChange={(e) => setPairingInput(e.target.value)}
                    className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2.5 text-center text-base tracking-widest font-sans font-bold text-white outline-none focus:border-[#2f81f7]"
                  />
                  <button
                    disabled={isPairing || !pairingInput.trim()}
                    onClick={async () => {
                      if (!pairingInput.trim()) return;
                      setIsPairing(true);
                      try {
                        const res = await syncApi.verifyPairingCode(pairingInput.trim());
                        setAuth({ token: res.token, user: res.user });
                        await handleSync(true);
                      } catch (err) {
      syncDelay.current = Math.min(syncDelay.current * 2, 300_000);
                        showToast(err.message);
                      } finally {
                        setIsPairing(false);
                      }
                    }}
                    className="w-full py-2.5 rounded-xl bg-[#2f81f7] hover:bg-[#1f6feb] disabled:opacity-40 text-white text-xs font-semibold transition-colors shadow-sm"
                  >
                    {isPairing ? '연결 중...' : '스마트폰과 연결하기'}
                  </button>
                </div>
              )}
            </div>

            {/* Quick Actions Card */}
            <div className="p-4 rounded-2xl bg-[#161b22] border border-[#30363d] space-y-3">
              <span className="text-xs font-bold text-[#f0f6fc]">위젯 편의 설정</span>
              <p className="text-[12px] text-[#8b949e]">{desktopStatus}</p>              <label className="block text-xs text-[#c9d1d9]">
                불투명도 {widgetOpacity}%
                <input aria-label="위젯 불투명도" type="range" min="30" max="100" value={widgetOpacity}
                  onChange={e => { const value = Number(e.target.value); setWidgetOpacity(value); localStorage.setItem('widget_opacity', String(value)); }}
                  className="block w-full mt-2 accent-[#58a6ff]" />
              </label>
              <p className="text-[12px] text-[#8b949e]">위치·크기는 상단 잠금 버튼으로 조정할 수 있습니다.</p>

              <div className="flex items-center justify-between py-1">
                <span className="text-xs text-[#c9d1d9]">공식 게임 이벤트 수집</span>
                <button
                  onClick={handleImportEvents}
                  className="px-2.5 py-1 rounded-lg bg-[#21262d] hover:bg-[#30363d] text-[#58a6ff] text-xs font-medium border border-[#30363d]"
                >
                  가져오기
                </button>
              </div>
            </div>

            {/* App Info Footer */}
            <div className="text-center pt-2 text-[12px] text-[#8b949e]">
              <p className="font-semibold text-[#f0f6fc]">Daily Check v1.3 (Desktop Widget)</p>
              <p className="mt-0.5">안드로이드 앱 및 Cloudflare D1 동기화 지원</p>
            </div>
          </div>
        )}

      </div>

      {/* Floating Action Button (FAB) for adding tasks in list tabs */}
      {(currentNav === 'Daily' || currentNav === 'Weekly' || currentNav === 'Event' || currentNav === 'Personal') && (
        <button
          onClick={() => {
            setAddCategory(currentNav);
            const pkg = registeredApps.includes(addGame) ? addGame : registeredApps[0] || '';
            loadAddReset(pkg, currentNav);
            setAddDays(0);
            setAddHours(0);
            if (currentNav === 'Personal') setAddGame('personal');
            else if (!registeredApps.includes(addGame)) setAddGame(registeredApps[0] || '');
            setNewTaskTime('');
            setShowAddModal(true);
          }}
          className="absolute bottom-16 right-4 w-12 h-12 rounded-2xl bg-[#2f81f7] hover:bg-[#1f6feb] text-white flex items-center justify-center shadow-lg transition-transform active:scale-95 z-20"
          title="새 숙제 추가"
        >
          <Plus className="w-5 h-5 stroke-[2.5]" />
        </button>
      )}

      {/* 3. Mobile-style Bottom Navigation Bar */}
      <div className="h-14 bg-[#161b22] border-t border-[#30363d] flex items-center justify-around px-2 shrink-0 z-30">
        {[
          { id: 'home', label: '홈', icon: Home },
          { id: 'Daily', label: '데일리', icon: CalendarCheck },
          { id: 'Weekly', label: '위클리', icon: CalendarDays },
          { id: 'Event', label: '이벤트', icon: Clock },
          { id: 'Personal', label: '일정', icon: CalendarDays },
          { id: 'more', label: '더보기', icon: MoreHorizontal },
        ].map(tab => {
          const active = currentNav === tab.id;
          const iconUrl = new URL(`./assets/nav-${tab.id}.svg`, import.meta.url).href;
          return (
            <button
              key={tab.id}
              onClick={() => setCurrentNav(tab.id)}
              className="flex-1 flex flex-col items-center justify-center py-1 transition-all"
            >
              <div className={`px-3 py-0.5 rounded-full transition-all ${
                active ? 'bg-[#1c2d4f] text-[#58a6ff]' : 'text-[#8b949e] hover:text-[#f0f6fc]'
              }`}>
                <span aria-hidden="true" className="block w-5 h-5 bg-current" style={{ mask: `url("${iconUrl}") center / contain no-repeat`, WebkitMask: `url("${iconUrl}") center / contain no-repeat` }} />
              </div>
              <span className={`text-[12px] mt-0.5 font-medium ${
                active ? 'text-[#58a6ff] font-semibold' : 'text-[#8b949e]'
              }`}>
                {tab.label}
              </span>
            </button>
          );
        })}
      </div>

      {actionTask && (
        <div className="fixed inset-0 z-50" onClick={() => setActionTask(null)} onKeyDown={event => { if (event.key === 'Escape') setActionTask(null); }}>
          <div role="menu" aria-label="항목 메뉴" style={{ top: actionTask.top }} className="fixed right-3 w-32 rounded-xl bg-[#21262d] border border-[#30363d] p-1 shadow-xl">
            <button autoFocus role="menuitem" onClick={event => { startEdit(actionTask.task, event); setActionTask(null); }} className="block w-full rounded-lg px-3 py-2 text-left text-xs hover:bg-[#30363d]">편집</button>
            <button role="menuitem" onClick={event => { deleteTask(actionTask.task, event); setActionTask(null); }} className="block w-full rounded-lg px-3 py-2 text-left text-xs text-[#f85149] hover:bg-[#30363d]">삭제</button>
          </div>
        </div>
      )}
      {editingTask && (
        <div className="absolute inset-0 bg-black/80 flex items-center justify-center p-4 z-50">
          <form onSubmit={saveEdit} role="dialog" aria-modal="true" aria-label="일정 편집" className="w-full rounded-2xl bg-[#161b22] border border-[#30363d] p-4 space-y-3">
            <h2 className="text-lg font-bold">일정 편집</h2>
            <label className="block text-[12px]">제목
              <input autoFocus required value={editTitle} onChange={event => setEditTitle(event.target.value)} className="block w-full mt-1 rounded-lg bg-[#21262d] border border-[#30363d] px-3 py-2 text-xs" />
            </label>
            {editingTask.category === 'Personal' && (
              <label className="block text-[12px]">시간 설정
                <DateTimePicker value={editTime} onChange={setEditTime} />
              </label>
            )}
            {editingTask.category === 'Event' && (
              <div className="space-y-2 text-[12px] text-[#8b949e]">
                <span>시간 설정</span>
                <div className="flex items-center gap-2">
                  <TimeNumberInput label="남은 일" max={3650} value={editDays} onChange={setEditDays} /><span>일</span>
                  <TimeNumberInput label="남은 시간" max={23} value={editHours} onChange={setEditHours} /><span>시간 후</span>
                </div>
              </div>
            )}
            <div className="flex justify-end gap-2">
              <button type="button" onClick={() => setEditingTask(null)} className="px-3 py-2 text-xs">취소</button>
              <button type="submit" disabled={!editTitle.trim() || (editingTask.category === 'Event' && editDays * 24 + editHours === 0 && (!(editingTask.deadline > 0) || originalEditHours !== 0))} className="rounded-lg bg-[#2f81f7] px-3 py-2 text-xs disabled:opacity-40">저장</button>
            </div>
          </form>
        </div>
      )}
      {/* Add Task Modal */}
      {showAddModal && (
        <div className="absolute inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="w-full bg-[#161b22] border border-[#30363d] rounded-2xl p-4 shadow-2xl">
            <h3 className="text-xs font-bold text-[#f0f6fc] mb-3 flex items-center gap-1.5">
              <Plus className="w-4 h-4 text-[#2f81f7]" /> {addCategory === 'Personal' ? '새 일정 추가' : `새 ${addCategory} 항목 추가`}
            </h3>
            <form onSubmit={handleAddTask} className="space-y-3">
              {addCategory !== 'Personal' && <div>
                <label className="text-[12px] text-[#8b949e] block mb-1">대상 게임 / 구분</label>
                <select
                  disabled={addCategory === 'Personal'}
                  value={addGame}
                  onChange={(e) => { setAddGame(e.target.value); loadAddReset(e.target.value, addCategory); }}
                  className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-[#2f81f7]"
                >
                  {addCategory === 'Personal' ? <option value="personal">개인 계획</option> : (
                    registeredApps.length ? registeredApps.map(pkg => <option key={pkg} value={pkg}>{appName(pkg)}</option>) : <option value="">등록된 앱 없음</option>
                  )}
                </select>
              </div>}
              <div>
                <label className="text-[12px] text-[#8b949e] block mb-1">할 일 제목</label>
                <input
                  type="text"
                  placeholder="예: 배터리 소모, 출석 체크 등"
                  value={newTaskTitle}
                  onChange={(e) => setNewTaskTitle(e.target.value)}
                  autoFocus
                  className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-[#2f81f7]"
                />
              </div>
              {addCategory === 'Personal' && (
                <label className="block text-[12px] text-[#8b949e]">
                  시간 설정
                  <DateTimePicker value={newTaskTime} onChange={setNewTaskTime} />
                </label>
              )}
              {(addCategory === 'Daily' || addCategory === 'Weekly') && (
                <div className="space-y-2 text-[12px] text-[#8b949e]">
                  <span>초기화 시간</span>
                  <div className="flex items-center gap-2">
                    {addCategory === 'Weekly' && <select aria-label="초기화 요일" value={addResetDay} onChange={event => setAddResetDay(Number(event.target.value))} className="rounded-lg bg-[#21262d] p-2 text-white">
                      {['일', '월', '화', '수', '목', '금', '토'].map((day, index) => <option key={day} value={index + 1}>{day}요일</option>)}
                    </select>}
                    <TimeNumberInput label="초기화 시" max={23} value={Math.floor(addResetMinute / 60)} onChange={hour => setAddResetMinute(hour * 60 + addResetMinute % 60)} /><span>시</span>
                    <TimeNumberInput label="초기화 분" max={59} value={addResetMinute % 60} onChange={minute => setAddResetMinute(Math.floor(addResetMinute / 60) * 60 + minute)} /><span>분</span>
                  </div>
                  <p>이 앱의 {addCategory === 'Daily' ? '데일리' : '위클리'} 전체에 적용됩니다.</p>
                </div>
              )}
              {addCategory === 'Event' && (
                <div className="space-y-2 text-[12px] text-[#8b949e]">
                  <span>시간 설정</span>
                  <div className="flex items-center gap-2">
                    <TimeNumberInput label="남은 일" max={3650} value={addDays} onChange={setAddDays} /><span>일</span>
                    <TimeNumberInput label="남은 시간" max={23} value={addHours} onChange={setAddHours} /><span>시간 후</span>
                  </div>
                </div>
              )}
              <div className="flex gap-2 justify-end pt-2">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="px-3 py-1.5 rounded-xl text-[#8b949e] hover:bg-[#21262d] text-xs font-medium"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={!newTaskTitle.trim() || !addGame}
                  className="px-4 py-1.5 rounded-xl bg-[#2f81f7] hover:bg-[#1f6feb] disabled:opacity-40 text-white text-xs font-semibold"
                >
                  추가
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

    </div>
  );
}




























