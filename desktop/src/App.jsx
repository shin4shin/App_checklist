import React, { useState, useEffect, useMemo } from 'react';
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
  Trash2, 
  ChevronDown, 
  ChevronUp, 
  Download
} from 'lucide-react';
import { syncApi } from './services/api';

const DEFAULT_TASKS = [];

export default function App() {
  const [tasks, setTasks] = useState(() => {
    const saved = localStorage.getItem('daily_check_tasks');
    return saved ? JSON.parse(saved) : DEFAULT_TASKS;
  });

  const [currentNav, setCurrentNav] = useState('home'); // 'home' | 'Daily' | 'Weekly' | 'Event' | 'more'
  const [expandedGames, setExpandedGames] = useState({
    'com.hoyoverse.nap': true,
    'com.hoyoverse.hkrpg': true,
    'personal': true,
  });

  const [isAlwaysOnTop, setIsAlwaysOnTop] = useState(false);
  const [auth, setAuth] = useState(() => syncApi.getStoredAuth());
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncToast, setSyncToast] = useState('');
  const [showAddModal, setShowAddModal] = useState(false);
  const [newTaskTitle, setNewTaskTitle] = useState('');
  const [addGame, setAddGame] = useState('com.hoyoverse.nap');
  const [addCategory, setAddCategory] = useState('Daily');
  const [pairingInput, setPairingInput] = useState('');
  const [isPairing, setIsPairing] = useState(false);

  // Persist tasks
  useEffect(() => {
    localStorage.setItem('daily_check_tasks', JSON.stringify(tasks));
  }, [tasks]);

  const toggleAlwaysOnTop = async () => {
    const nextState = !isAlwaysOnTop;
    setIsAlwaysOnTop(nextState);
    try {
      if (window.__TAURI__?.window) {
        const { getCurrentWindow } = window.__TAURI__.window;
        await getCurrentWindow().setAlwaysOnTop(nextState);
      }
    } catch {
      // preview environment fallback
    }
  };

  const showToast = (msg) => {
    setSyncToast(msg);
    setTimeout(() => setSyncToast(''), 2500);
  };

  const handleSync = async () => {
    if (!auth) {
      setCurrentNav('more');
      showToast('Google 로그인이 필요합니다');
      return;
    }
    setIsSyncing(true);
    try {
      const lastSync = parseInt(localStorage.getItem('daily_check_last_sync') || '0', 10);
      const res = await syncApi.sync({
        tasks,
        settings: [],
        since: lastSync,
      });

      const serverTasks = res.serverChanges?.tasks || [];
      if (serverTasks.length > 0) {
        setTasks(prev => {
          const map = new Map(prev.map(t => [`${t.packageName}_${t.category}_${t.taskId}`, t]));
          for (const st of serverTasks) {
            const key = `${st.packageName}_${st.category}_${st.taskId}`;
            if (st.isDeleted) {
              map.delete(key);
            } else {
              const gameName = st.packageName.includes('nap') ? '젠레스 존 제로' 
                : st.packageName.includes('hkrpg') ? '붕괴: 스타레일' 
                : st.packageName === 'personal' ? '개인 계획' : st.packageName;
              map.set(key, { ...st, gameName });
            }
          }
          return Array.from(map.values());
        });
      }

      localStorage.setItem('daily_check_last_sync', String(res.syncedAt || Date.now()));
      showToast('동기화 완료');
    } catch (err) {
      showToast(err.message || '동기화 실패');
    } finally {
      setIsSyncing(false);
    }
  };

  const toggleTask = (task) => {
    const now = Date.now();
    setTasks(prev => prev.map(t => {
      if (t.packageName === task.packageName && t.category === task.category && t.taskId === task.taskId) {
        return { ...t, done: !t.done, updatedAt: now };
      }
      return t;
    }));
  };

  const deleteTask = (task, e) => {
    e.stopPropagation();
    const now = Date.now();
    setTasks(prev => prev.map(t => {
      if (t.packageName === task.packageName && t.category === task.category && t.taskId === task.taskId) {
        return { ...t, isDeleted: true, updatedAt: now };
      }
      return t;
    }).filter(t => !t.isDeleted));
  };

  const handleAddTask = (e) => {
    e.preventDefault();
    if (!newTaskTitle.trim()) return;
    const gameName = addGame.includes('nap') ? '젠레스 존 제로'
      : addGame.includes('hkrpg') ? '붕괴: 스타레일'
      : addGame === 'personal' ? '개인 계획' : '기타 게임';

    const newTask = {
      packageName: addGame,
      gameName,
      category: addCategory,
      taskId: 'task_' + Date.now().toString(36),
      title: newTaskTitle.trim(),
      done: false,
      deadline: 0,
      routineDays: 0,
      resetMinute: 240,
      updatedAt: Date.now(),
    };

    setTasks(prev => [newTask, ...prev]);
    setNewTaskTitle('');
    setShowAddModal(false);
    showToast('숙제 추가됨');
  };

  const toggleGameExpanded = (pkg) => {
    setExpandedGames(prev => ({ ...prev, [pkg]: !prev[pkg] }));
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
  const dailyTasks = useMemo(() => tasks.filter(t => t.category === 'Daily' && !t.isDeleted), [tasks]);
  const dailyDone = useMemo(() => dailyTasks.filter(t => t.done).length, [dailyTasks]);
  const dailyTotal = dailyTasks.length;
  const progressPercent = dailyTotal > 0 ? Math.round((dailyDone / dailyTotal) * 100) : 0;
  const pendingTasks = useMemo(() => tasks.filter(t => !t.done && !t.isDeleted), [tasks]);
  const eventTasks = useMemo(() => tasks.filter(t => t.category === 'Event' && !t.isDeleted), [tasks]);

  const todayDateStr = useMemo(() => {
    const d = new Date();
    const days = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'];
    return `${d.getMonth() + 1}월 ${d.getDate()}일 ${days[d.getDay()]}`;
  }, []);

  return (
    <div className="w-[360px] h-[580px] bg-[#0d1117] text-[#f0f6fc] rounded-[22px] flex flex-col border border-[#30363d] shadow-2xl overflow-hidden font-sans select-none relative">
      
      {/* 1. Mobile Status Bar & Window Header (Draggable) */}
      <div 
        data-tauri-drag-region 
        className="h-10 px-4 bg-[#161b22] border-b border-[#30363d] flex items-center justify-between cursor-grab active:cursor-grabbing shrink-0"
      >
        <div className="flex items-center gap-2 pointer-events-none">
          <div className="w-2 h-2 rounded-full bg-[#2f81f7]" />
          <span className="font-semibold text-xs tracking-tight text-[#f0f6fc]">Daily Check</span>
        </div>

        <div className="flex items-center gap-1.5">
          <button 
            onClick={toggleAlwaysOnTop}
            title={isAlwaysOnTop ? "항상 위 끄기" : "항상 위 켜기"}
            className={`p-1 rounded-md transition-colors ${isAlwaysOnTop ? 'text-[#58a6ff] bg-[#1c2d4f]' : 'text-[#8b949e] hover:text-[#f0f6fc] hover:bg-[#21262d]'}`}
          >
            {isAlwaysOnTop ? <Pin className="w-3.5 h-3.5" /> : <PinOff className="w-3.5 h-3.5" />}
          </button>

          <button 
            onClick={handleSync}
            disabled={isSyncing}
            title="동기화"
            className="p-1 rounded-md text-[#8b949e] hover:text-[#f0f6fc] hover:bg-[#21262d] transition-colors"
          >
            <RotateCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin text-[#2f81f7]' : ''}`} />
          </button>
        </div>
      </div>

      {/* Toast Notification */}
      {syncToast && (
        <div className="absolute top-12 left-1/2 -translate-x-1/2 z-50 px-3 py-1 bg-[#1c2d4f] border border-[#2f81f7]/50 text-[#58a6ff] text-[11px] font-medium rounded-full shadow-lg pointer-events-none animate-bounce">
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
                <span className="text-4xl font-extrabold tracking-tight">{progressPercent}%</span>
                <span className="text-xs font-medium text-[#c9d1d9]">{dailyDone}/{dailyTotal} 완료</span>
              </div>

              {/* Linear Progress Bar */}
              <div className="w-full h-2 bg-[#0d1117]/60 rounded-full mt-3.5 overflow-hidden">
                <div 
                  className="h-full bg-[#58a6ff] transition-all duration-500 rounded-full"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>

              <p className="text-[11px] text-[#58a6ff] mt-3 font-medium">
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
                        <div className={`w-4 h-4 rounded-md flex items-center justify-center shrink-0 ${
                          task.done ? 'bg-[#2f81f7] text-white' : 'border border-[#8b949e]'
                        }`}>
                          {task.done && <Check className="w-3 h-3 stroke-[3]" />}
                        </div>
                        <div className="min-w-0">
                          <p className={`text-xs font-medium truncate ${task.done ? 'line-through text-[#8b949e]' : 'text-[#f0f6fc]'}`}>
                            {task.title}
                          </p>
                          <p className="text-[10px] text-[#8b949e]">{task.gameName}</p>
                        </div>
                      </div>
                      <span className="text-[10px] px-2 py-0.5 rounded-full bg-[#21262d] text-[#58a6ff] font-mono shrink-0">
                        진행 중
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
                <span className="text-[11px] text-[#8b949e]">{pendingTasks.length}개 남음</span>
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
                        <div className="w-4 h-4 rounded-md border border-[#8b949e] shrink-0" />
                        <div className="min-w-0">
                          <p className="text-xs font-medium text-[#f0f6fc] truncate">{task.title}</p>
                          <p className="text-[10px] text-[#8b949e]">{task.gameName} · {task.category}</p>
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
        {(currentNav === 'Daily' || currentNav === 'Weekly' || currentNav === 'Event') && (
          <div className="p-4 space-y-4 animate-fade-in pb-16">
            {/* Title Bar */}
            <div className="flex items-center justify-between">
              <div>
                <h1 className="text-lg font-bold text-[#f0f6fc]">
                  {currentNav === 'Daily' ? '데일리 숙제' : currentNav === 'Weekly' ? '위클리 숙제' : '이벤트 숙제'}
                </h1>
                <p className="text-[11px] text-[#8b949e]">
                  {tasks.filter(t => t.category === currentNav && !t.isDeleted).length}개의 등록된 항목
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

            {/* Game Cards (Accordion List like Mobile App) */}
            {['com.hoyoverse.nap', 'com.hoyoverse.hkrpg', 'personal'].map(pkg => {
              const gameTasks = tasks.filter(t => t.packageName === pkg && t.category === currentNav && !t.isDeleted);
              if (gameTasks.length === 0) return null;

              const gameName = pkg === 'com.hoyoverse.nap' ? '젠레스 존 제로'
                : pkg === 'com.hoyoverse.hkrpg' ? '붕괴: 스타레일' : '개인 계획';
              const doneCount = gameTasks.filter(t => t.done).length;
              const allDone = doneCount === gameTasks.length;
              const isExpanded = expandedGames[pkg] !== false;

              return (
                <div key={pkg} className="rounded-2xl bg-[#161b22] border border-[#30363d] overflow-hidden">
                  {/* Game Card Header */}
                  <div 
                    onClick={() => toggleGameExpanded(pkg)}
                    className="p-3.5 flex items-center justify-between cursor-pointer hover:bg-[#21262d]/50 transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-xl bg-[#21262d] flex items-center justify-center font-bold text-xs text-[#58a6ff] border border-[#30363d]">
                        {gameName.slice(0, 1)}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-[#f0f6fc]">{gameName}</span>
                          {allDone && (
                            <span className="text-[10px] px-1.5 py-0.5 bg-[#2f81f7]/20 text-[#58a6ff] rounded font-medium">
                              완료 ✓
                            </span>
                          )}
                        </div>
                        <p className="text-[10px] text-[#8b949e] mt-0.5">
                          {pkg === 'personal' ? '루틴' : '초기화 04:00'} · {doneCount}/{gameTasks.length} 완료
                        </p>
                      </div>
                    </div>

                    <div className="text-[#8b949e]">
                      {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </div>
                  </div>

                  {/* Tasks under this Game */}
                  {isExpanded && (
                    <div className="border-t border-[#30363d] divide-y divide-[#30363d]/60 bg-[#0d1117]/40">
                      {gameTasks.map(task => (
                        <div
                          key={task.taskId}
                          onClick={() => toggleTask(task)}
                          className="px-4 py-2.5 flex items-center justify-between group cursor-pointer hover:bg-[#21262d]/40 transition-colors"
                        >
                          <div className="flex items-center gap-3 min-w-0 pr-2">
                            <div className={`w-4 h-4 rounded flex items-center justify-center shrink-0 transition-colors ${
                              task.done ? 'bg-[#2f81f7] text-white' : 'border border-[#8b949e] group-hover:border-[#58a6ff]'
                            }`}>
                              {task.done && <Check className="w-3 h-3 stroke-[3]" />}
                            </div>
                            <span className={`text-xs truncate ${
                              task.done ? 'line-through text-[#8b949e]' : 'text-[#f0f6fc]'
                            }`}>
                              {task.title}
                            </span>
                          </div>

                          <button
                            onClick={(e) => deleteTask(task, e)}
                            className="opacity-0 group-hover:opacity-100 p-1 text-[#8b949e] hover:text-[#f85149] transition-opacity"
                            title="삭제"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}

            {tasks.filter(t => t.category === currentNav && !t.isDeleted).length === 0 && (
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
                <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                  auth ? 'bg-emerald-500/20 text-emerald-400' : 'bg-[#21262d] text-[#8b949e]'
                }`}>
                  {auth ? '연동됨' : '미연동'}
                </span>
              </div>

              {auth ? (
                <div className="space-y-3 pt-1">
                  <div className="p-2.5 rounded-xl bg-[#21262d] border border-[#30363d]">
                    <p className="text-[10px] text-[#8b949e]">연동 상태</p>
                    <p className="text-xs font-semibold text-emerald-400 mt-0.5">스마트폰과 실시간 연결됨 ✓</p>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={handleSync}
                      disabled={isSyncing}
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
                  <p className="text-[11px] text-[#8b949e] leading-relaxed">
                    스마트폰 앱의 <strong className="text-[#f0f6fc]">[더보기 ➔ 연동 코드]</strong>를 누르고 표시된 6자리 번호를 입력하세요.
                  </p>
                  <input
                    type="text"
                    maxLength={7}
                    placeholder="예: 842-195"
                    value={pairingInput}
                    onChange={(e) => setPairingInput(e.target.value)}
                    className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2.5 text-center text-base tracking-widest font-mono font-bold text-white outline-none focus:border-[#2f81f7]"
                  />
                  <button
                    disabled={isPairing || !pairingInput.trim()}
                    onClick={async () => {
                      if (!pairingInput.trim()) return;
                      setIsPairing(true);
                      try {
                        const res = await syncApi.verifyPairingCode(pairingInput.trim());
                        setAuth({ token: res.token, user: res.user });
                        showToast('스마트폰과 연결되었습니다! ✨');
                        // Immediately fetch mobile tasks
                        setTimeout(() => handleSync(), 100);
                      } catch (err) {
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
              <div className="flex items-center justify-between py-1 border-b border-[#30363d]">
                <span className="text-xs text-[#c9d1d9]">바탕화면 항상 위 고정</span>
                <button
                  onClick={toggleAlwaysOnTop}
                  className={`w-10 h-6 rounded-full transition-colors relative ${
                    isAlwaysOnTop ? 'bg-[#2f81f7]' : 'bg-[#21262d]'
                  }`}
                >
                  <div className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-all ${
                    isAlwaysOnTop ? 'right-1' : 'left-1'
                  }`} />
                </button>
              </div>

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
            <div className="text-center pt-2 text-[11px] text-[#8b949e]">
              <p className="font-semibold text-[#f0f6fc]">Daily Check v1.3 (Desktop Widget)</p>
              <p className="mt-0.5">안드로이드 앱 및 Cloudflare D1 동기화 지원</p>
            </div>
          </div>
        )}

      </div>

      {/* Floating Action Button (FAB) for adding tasks in list tabs */}
      {(currentNav === 'Daily' || currentNav === 'Weekly' || currentNav === 'Event') && (
        <button
          onClick={() => {
            setAddCategory(currentNav);
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
          { id: 'more', label: '더보기', icon: MoreHorizontal },
        ].map(tab => {
          const active = currentNav === tab.id;
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              onClick={() => setCurrentNav(tab.id)}
              className="flex-1 flex flex-col items-center justify-center py-1 transition-all"
            >
              <div className={`px-3 py-0.5 rounded-full transition-all ${
                active ? 'bg-[#1c2d4f] text-[#58a6ff]' : 'text-[#8b949e] hover:text-[#f0f6fc]'
              }`}>
                <Icon className={`w-4 h-4 ${active ? 'stroke-[2.5]' : 'stroke-[1.8]'}`} />
              </div>
              <span className={`text-[10px] mt-0.5 font-medium ${
                active ? 'text-[#58a6ff] font-semibold' : 'text-[#8b949e]'
              }`}>
                {tab.label}
              </span>
            </button>
          );
        })}
      </div>

      {/* Add Task Modal */}
      {showAddModal && (
        <div className="absolute inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="w-full bg-[#161b22] border border-[#30363d] rounded-2xl p-4 shadow-2xl">
            <h3 className="text-xs font-bold text-[#f0f6fc] mb-3 flex items-center gap-1.5">
              <Plus className="w-4 h-4 text-[#2f81f7]" /> 새 {addCategory} 항목 추가
            </h3>
            <form onSubmit={handleAddTask} className="space-y-3">
              <div>
                <label className="text-[10px] text-[#8b949e] block mb-1">대상 게임 / 구분</label>
                <select
                  value={addGame}
                  onChange={(e) => setAddGame(e.target.value)}
                  className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-[#2f81f7]"
                >
                  <option value="com.hoyoverse.nap">젠레스 존 제로</option>
                  <option value="com.hoyoverse.hkrpg">붕괴: 스타레일</option>
                  <option value="personal">개인 계획</option>
                  <option value="custom">기타 게임</option>
                </select>
              </div>
              <div>
                <label className="text-[10px] text-[#8b949e] block mb-1">할 일 제목</label>
                <input
                  type="text"
                  placeholder="예: 배터리 소모, 출석 체크 등"
                  value={newTaskTitle}
                  onChange={(e) => setNewTaskTitle(e.target.value)}
                  autoFocus
                  className="w-full bg-[#21262d] border border-[#30363d] rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-[#2f81f7]"
                />
              </div>
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
                  disabled={!newTaskTitle.trim()}
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
