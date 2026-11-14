/**
 * 档案库分析页：档案库资源用量、分类、标签、大小、上传趋势与（管理员的）档案库排行
 * <p>
 * 进入本路由后按需按序加载本地 ECharts 与词云扩展。
 * 支持两种视角：
 * 1）用户：从自己的档案库里选一个分析；
 * 2）管理员：可额外选择「全部档案库（汇总）」，并查看档案库使用排行。
 */
import { archiveApi, archiveAnalyzeApi } from '../api.js';
import {
  $, $$, toast, escapeHtml, formatSize, renderEmpty, state,
  revealOnScroll, attachRippleAll,
} from '../ui.js';

/** 图表主色板，与深色主题保持一致观感（提亮保证深底上的对比度） */
const PALETTE = ['#4d8dff', '#2dd4bf', '#fbbf24', '#f06a6f', '#b18cff', '#38bdf8', '#a3e635', '#f472b6'];

/** 时间维度文案与值，折线图切换用 */
const DIMENSIONS = [
  { value: 'day', text: '按天' },
  { value: 'week', text: '按周' },
  { value: 'month', text: '按月' },
];

/** 当前分析上下文 */
let current = {
  /** 档案库 id；null 表示全部档案库（仅管理员可选） */
  archiveId: null,
  timeDimension: 'day',
};

/** 按容器复用 echarts 实例，避免切换数据时重复 init */
const charts = new Map();

/** 每个图表容器自己的请求序号，旧请求不能覆盖新数据 */
const requestGenerations = new Map();

/** 当前视图序号，路由销毁后所有异步回调失效 */
let viewGeneration = 0;

/** 当前图表对应的原始数据，用于导出 CSV 报表 */
const chartData = new Map();
let currentArchiveName = '';

/** 图表库加载 Promise：成功后复用，失败后清空以便重试 */
let chartLibrariesPromise = null;

/** 窗口尺寸变化时让所有图表自适应 */
function handleResize() {
  charts.forEach((chart) => chart.resize());
}

/** 是否具备图表能力 */
function hasEcharts() {
  return typeof window !== 'undefined' && !!window.echarts;
}

/** 动态加载单个本地脚本 */
function loadScript(src) {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.async = false;
    script.onload = () => {
      script.onload = null;
      script.onerror = null;
      resolve();
    };
    script.onerror = () => {
      script.onload = null;
      script.onerror = null;
      reject(new Error(`图表组件加载失败：${src}`));
    };
    (document.head || document.body).appendChild(script);
  });
}

/** 按序加载 ECharts 和词云扩展；并发调用共享同一个 Promise */
function loadChartLibraries() {
  if (chartLibrariesPromise) {
    return chartLibrariesPromise;
  }
  chartLibrariesPromise = (async () => {
    if (!hasEcharts()) {
      await loadScript(new URL('../../lib/echarts.min.js', import.meta.url).href);
    }
    await loadScript(new URL('../../lib/echarts-wordcloud.min.js', import.meta.url).href);
    if (!hasEcharts()) {
      throw new Error('图表组件加载完成，但 ECharts 不可用');
    }
  })().catch((e) => {
    chartLibrariesPromise = null;
    throw e;
  });
  return chartLibrariesPromise;
}

/** 当前视图与当前容器请求是否仍然有效 */
function isCurrentRequest(el, generation, boxId, requestGeneration) {
  return generation === viewGeneration
    && el.isConnected !== false
    && requestGenerations.get(boxId) === requestGeneration;
}

/** 释放某个容器上的图表实例 */
function disposeChart(container) {
  const chart = charts.get(container);
  if (chart) {
    try {
      chart.dispose();
    } catch (e) {
      /* 已销毁的实例无需重复处理 */
    }
    charts.delete(container);
  }
}

/** 清空图表并显示加载、空态或错误信息 */
function showChartMessage(container, message) {
  disposeChart(container);
  container.innerHTML = `<div class="chart-empty">${escapeHtml(message)}</div>`;
}

/** 释放本视图创建的全部实例 */
function destroyCharts() {
  charts.forEach((chart) => {
    try {
      chart.dispose();
    } catch (e) {
      /* 忽略重复销毁 */
    }
  });
  charts.clear();
}

/**
 * 在指定容器上渲染图表，同一容器始终复用已有实例
 *
 * @param {HTMLElement} container 容器（需要有确定高度）
 * @param {object}      option    echarts option
 * @returns {object|null} 实例；渲染异常时返回 null
 */
function mountChart(container, option) {
  if (!hasEcharts() || !container) {
    return null;
  }
  let chart = charts.get(container);
  try {
    if (chart && typeof chart.isDisposed === 'function' && chart.isDisposed()) {
      charts.delete(container);
      chart = null;
    }
    if (!chart) {
      container.innerHTML = '';
      // 深色主题：内置 dark 主题让文字/轴线/图例变浅，背景透明以贴合卡片底色。
      chart = window.echarts.init(container, 'dark');
      charts.set(container, chart);
    }
    chart.setOption({ backgroundColor: 'transparent' });
    chart.setOption(option, true);
    return chart;
  } catch (e) {
    if (chart) {
      try {
        chart.dispose();
      } catch (disposeError) {
        /* 忽略清理异常 */
      }
    }
    charts.delete(container);
    return null;
  }
}

/* ------------------------------ 各图表的 option ------------------------------ */

/** 资源用量仪表盘（容量 / 数量两个表盘） */
function usageOption(title, ratio, subText) {
  return {
    series: [{
      type: 'gauge',
      startAngle: 220,
      endAngle: -40,
      min: 0,
      max: 100,
      progress: { show: true, width: 14 },
      axisLine: { lineStyle: { width: 14 } },
      axisTick: { show: false },
      splitLine: { length: 6 },
      axisLabel: { show: false },
      pointer: { show: false },
      anchor: { show: false },
      detail: {
        valueAnimation: true,
        formatter: '{value}%',
        fontSize: 22,
        offsetCenter: [0, '10%'],
      },
      title: { offsetCenter: [0, '42%'], fontSize: 12 },
      data: [{ value: Math.min(ratio ?? 0, 100), name: subText }],
      itemStyle: { color: ratio >= 90 ? '#f06a6f' : PALETTE[0] },
    }],
    title: { text: title, left: 'center', top: 0, textStyle: { fontSize: 14 } },
  };
}

/** 分类分组条形图：同一分类同时展示数量与总大小（双 y 轴） */
function categoryOption(rows) {
  const categories = rows.map((row) => row.category);
  return {
    color: [PALETTE[0], PALETTE[2]],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 0 },
    grid: { left: 48, right: 56, bottom: 32, top: 36 },
    xAxis: { type: 'category', data: categories, axisLabel: { interval: 0, rotate: categories.length > 6 ? 30 : 0 } },
    yAxis: [
      { type: 'value', name: '数量（张）' },
      { type: 'value', name: '大小（MB）', splitLine: { show: false } },
    ],
    series: [
      { name: '影像数量', type: 'bar', data: rows.map((row) => row.count), barMaxWidth: 28 },
      {
        name: '总大小',
        type: 'bar',
        yAxisIndex: 1,
        data: rows.map((row) => Math.round((row.totalSize / 1048576) * 100) / 100),
        barMaxWidth: 28,
      },
    ],
  };
}

/** 标签词云；词云扩展不可用时降级为横向条形图 */
function tagOption(rows) {
  const data = rows.map((row) => ({ name: row.tag, value: row.count }));
  return {
    tooltip: { show: true },
    series: [{
      type: 'wordCloud',
      shape: 'circle',
      width: '92%',
      height: '92%',
      sizeRange: [14, 48],
      rotationRange: [0, 0],
      gridSize: 8,
      textStyle: {
        fontFamily: 'inherit',
        color: () => PALETTE[Math.floor(Math.random() * PALETTE.length)],
      },
      data,
    }],
  };
}

/** 标签条形图降级版 */
function tagBarOption(rows) {
  const sorted = [...rows].reverse();
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 32, top: 12, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: sorted.map((row) => row.tag) },
    series: [{
      name: '关联影像数',
      type: 'bar',
      data: sorted.map((row) => row.count),
      itemStyle: { color: PALETTE[1] },
      barMaxWidth: 18,
      label: { show: true, position: 'right' },
    }],
  };
}

/** 大小分段饼图 */
function sizeOption(rows) {
  return {
    color: PALETTE,
    tooltip: { trigger: 'item', formatter: '{b}：{c} 张（{d}%）' },
    legend: { bottom: 0 },
    series: [{
      name: '影像大小',
      type: 'pie',
      radius: ['38%', '66%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      label: { formatter: '{b}\n{c} 张' },
      data: rows.map((row) => ({ name: row.sizeRange, value: row.count })),
    }],
  };
}

/** 上传趋势折线图 */
function trendOption(rows) {
  return {
    color: [PALETTE[0]],
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 32, bottom: 32, top: 24 },
    xAxis: { type: 'category', data: rows.map((row) => row.date), boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      name: '上传数量',
      type: 'line',
      smooth: true,
      data: rows.map((row) => row.count),
      areaStyle: { opacity: 0.12 },
    }],
  };
}

/** 档案库使用排行柱状图（管理员） */
function rankOption(rows) {
  const sorted = [...rows].reverse();
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const item = params[0];
        const row = sorted[item.dataIndex];
        return `${escapeHtml(row.archiveName)}<br/>已用 ${formatSize(row.totalSize)}（${row.sizeRatio}%）<br/>影像 ${row.totalCount} 张`;
      },
    },
    grid: { left: 110, right: 48, top: 12, bottom: 24 },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: sorted.map((row) => row.archiveName),
      axisLabel: { width: 90, overflow: 'truncate' },
    },
    series: [{
      name: '已用容量（MB）',
      type: 'bar',
      data: sorted.map((row) => Math.round((row.totalSize / 1048576) * 100) / 100),
      itemStyle: { color: PALETTE[3] },
      barMaxWidth: 18,
      label: { show: true, position: 'right', formatter: '{c} MB' },
    }],
  };
}

/* ------------------------------ 报表导出 ------------------------------ */

function syncExportButton(el) {
  const button = $('#export-report', el);
  if (button) {
    button.disabled = ![...chartData.values()].some((rows) => rows && rows.length > 0);
  }
}

/** CSV 单元格转义，并阻止表格软件把用户输入当成公式执行。 */
function csvCell(value) {
  let text = value === null || value === undefined ? '' : String(value);
  if (/^[\s]*[=+\-@]/.test(text)) {
    text = `'${text}`;
  }
  return `"${text.replace(/"/g, '""')}"`;
}

function exportAnalysis(el) {
  if (![...chartData.values()].some((rows) => rows && rows.length > 0)) {
    toast('暂无可导出的分析数据', 'info');
    return;
  }
  const rows = [
    ['证链 TraceProof · 档案库分析报表'],
    ['档案库', currentArchiveName || '全部档案库'],
    ['导出时间', new Date().toLocaleString('zh-CN')],
    [],
  ];
  const section = (title, headers, data) => {
    if (!data || data.length === 0) {
      return;
    }
    rows.push([title], headers, ...data, []);
  };
  const usageRows = chartData.get('usage-size-box') || [];
  const usage = usageRows[0];
  section('资源用量', ['已用容量', '容量配额', '容量使用率', '已用数量', '数量配额', '数量使用率'], usage ? [[
    formatSize(usage.usedSize), formatSize(usage.maxSize), `${usage.sizeRatio || 0}%`,
    usage.usedCount || 0, usage.maxCount || 0, `${usage.countRatio || 0}%`,
  ]] : []);
  section('影像分类分布', ['分类', '数量（张）', '总大小（字节）'],
    (chartData.get('category-box') || []).map((row) => [row.category, row.count, row.totalSize]));
  section('标签分布', ['标签', '关联影像数'],
    (chartData.get('tag-box') || []).map((row) => [row.tag, row.count]));
  section('影像大小分布', ['大小区间', '数量（张）'],
    (chartData.get('size-box') || []).map((row) => [row.sizeRange, row.count]));
  section('上传趋势', ['时间', '上传数量（张）'],
    (chartData.get('trend-box') || []).map((row) => [row.date, row.count]));
  section('档案库使用排行', ['档案库', '已用容量（字节）', '影像数量', '容量占比'],
    (chartData.get('rank-box') || []).map((row) => [row.archiveName, row.totalSize, row.totalCount, `${row.sizeRatio || 0}%`]));

  const csv = `\ufeff${rows.map((row) => row.map(csvCell).join(',')).join('\r\n')}`;
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `证链分析报表-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
  toast('分析报表已导出', 'success');
}

/* ------------------------------ 数据加载 ------------------------------ */

/**
 * 通用加载：调用接口、渲染或显示错误；每次调用都有独立的容器请求序号。
 *
 * @param {HTMLElement} el      视图容器
 * @param {string}      boxId   图表容器 id
 * @param {Function}    fetcher 返回接口 Promise
 * @param {Function}    toOption 把数据转成 echarts option（返回 null 表示无数据）
 * @param {string}      emptyText 无数据提示
 * @param {number}      generation 当前视图序号
 * @param {Function}    [fallbackToOption] 主图表渲染失败时的降级 option
 */
async function loadChart(el, boxId, fetcher, toOption, emptyText, generation, fallbackToOption = null) {
  const requestGeneration = (requestGenerations.get(boxId) || 0) + 1;
  requestGenerations.set(boxId, requestGeneration);
  const box = $(`#${boxId}`, el);
  if (!box) {
    return;
  }
  if (!charts.has(box)) {
    showChartMessage(box, '数据加载中…');
  }
  const isCurrent = () => isCurrentRequest(el, generation, boxId, requestGeneration);
  try {
    const rows = await fetcher();
    if (!isCurrent()) {
      return;
    }
    chartData.set(boxId, rows || []);
    syncExportButton(el);
    const option = rows && rows.length > 0 ? toOption(rows) : null;
    if (!option) {
      showChartMessage(box, emptyText);
      return;
    }
    let chart = mountChart(box, option);
    if (!chart && fallbackToOption && isCurrent()) {
      chart = mountChart(box, fallbackToOption(rows));
    }
    if (!chart && isCurrent()) {
      showChartMessage(box, '图表组件未加载，无法展示');
    }
  } catch (e) {
    if (isCurrent()) {
      chartData.delete(boxId);
      syncExportButton(el);
      showChartMessage(box, e.message);
    }
  }
}

/** 拉取并渲染全部分析图表 */
function loadAll(el, generation) {
  if (generation !== viewGeneration || el.isConnected === false) {
    return;
  }
  const scope = { archiveId: current.archiveId };
  chartData.clear();
  syncExportButton(el);
  // 两个仪表盘共享同一次 usage 请求。
  const usagePromise = loadChartLibraries()
    .then(() => archiveAnalyzeApi.usage(scope))
    .then((vo) => (vo ? [vo] : []));

  loadChart(el, 'usage-size-box',
    () => usagePromise,
    (rows) => usageOption('容量使用', rows[0].sizeRatio,
      scope.archiveId ? '档案库容量' : '全系统容量'),
    '暂无用量数据', generation);

  loadChart(el, 'usage-count-box',
    () => usagePromise,
    (rows) => usageOption('数量使用', rows[0].countRatio,
      scope.archiveId ? '档案库影像数' : '全系统影像数'),
    '暂无用量数据', generation);

  loadChart(el, 'category-box',
    () => loadChartLibraries().then(() => archiveAnalyzeApi.category(scope)),
    categoryOption,
    '还没有影像或未设置分类', generation);

  // 标签：优先词云，词云扩展缺失时降级为条形图。
  loadChart(el, 'tag-box',
    () => loadChartLibraries().then(() => archiveAnalyzeApi.tag(scope)),
    tagOption,
    '还没有标签，上传影像时可以给影像打标签', generation, tagBarOption);

  loadChart(el, 'size-box',
    () => loadChartLibraries().then(() => archiveAnalyzeApi.size(scope)),
    sizeOption,
    '还没有影像', generation);

  const trendScope = { ...scope, timeDimension: current.timeDimension };
  loadChart(el, 'trend-box',
    () => loadChartLibraries().then(() => archiveAnalyzeApi.trend(trendScope)),
    trendOption,
    '还没有上传记录', generation);

  // 档案库使用排行：仅管理员且处于全档案库视角时展示。
  const rankCard = $('#rank-card', el);
  if (rankCard) {
    const visible = state.isAdmin() && scope.archiveId == null;
    rankCard.hidden = !visible;
    if (visible) {
      loadChart(el, 'rank-box',
        () => loadChartLibraries().then(() => archiveAnalyzeApi.rank({ limit: 10 })),
        rankOption,
        '还没有档案库数据', generation);
    } else {
      // 隐藏排行时也让旧请求失效并释放旧图表。
      requestGenerations.set('rank-box', (requestGenerations.get('rank-box') || 0) + 1);
      const rankBox = $('#rank-box', el);
      chartData.delete('rank-box');
      if (rankBox) {
        disposeChart(rankBox);
        rankBox.innerHTML = '';
      }
    }
  }
}

/* ------------------------------ 档案库选择 ------------------------------ */

/** 加载档案库选择器：普通用户列出自己的档案库，管理员额外有「全部档案库」 */
async function loadArchiveOptions(el, preselectId, generation) {
  const select = $('#archive-select', el);
  if (!select) {
    return;
  }
  try {
    // 数据与图表库并行准备，两个本地脚本自身仍严格按序加载。
    const [page] = await Promise.all([
      archiveApi.myListVOByPage({ current: 1, pageSize: 100 }),
      loadChartLibraries(),
    ]);
    if (generation !== viewGeneration || el.isConnected === false) {
      return;
    }
    const archives = (page && page.records) || [];

    const options = [];
    if (state.isAdmin()) {
      options.push('<option value="">全部档案库（公共案例库 + 所有档案库）</option>');
    }
    archives.forEach((archive) => {
      options.push(`<option value="${archive.id}">${escapeHtml(archive.archiveName)}</option>`);
    });

    if (archives.length === 0 && !state.isAdmin()) {
      select.innerHTML = '<option value="">暂无档案库</option>';
      $('#analyze-body', el).innerHTML = renderEmpty('你还没有档案库，先到「我的档案库」创建一个吧');
      return;
    }

    select.innerHTML = options.join('');
    // 优先用路由参数指定的档案库，其次保持全档案库视角（管理员），否则选第一个档案库
    const initial = preselectId || select.options[0].value;
    select.value = initial;
    current.archiveId = initial === '' ? null : initial;
    currentArchiveName = select.options[select.selectedIndex]
      ? select.options[select.selectedIndex].textContent
      : '';
    loadAll(el, generation);
  } catch (e) {
    if (generation === viewGeneration && el.isConnected !== false) {
      $('#analyze-body', el).innerHTML = renderEmpty(e.message);
    }
  }
}

/* ------------------------------ 视图 ------------------------------ */

export default {
  /**
   * 渲染分析页
   *
   * @param {HTMLElement} el    视图容器
   * @param {object}      query 路由查询参数，支持 ?archiveId= 指定档案库
   */
  render(el, query = {}) {
    viewGeneration += 1;
    const generation = viewGeneration;
    requestGenerations.clear();
    window.removeEventListener('resize', handleResize);
    destroyCharts();
    chartData.clear();
    currentArchiveName = '';
    current = { archiveId: null, timeDimension: 'day' };

    el.innerHTML = `
      <section class="analyze-toolbar">
        <div>
          <h2 class="page-title">档案库分析</h2>
          <p class="page-sub">从影像分布到上传趋势，快速定位异常件特征</p>
        </div>
        <div class="analyze-actions">
          <select id="archive-select" class="input input-select" aria-label="选择要分析的档案库"></select>
          <button class="btn" id="export-report" type="button" disabled>导出 CSV</button>
        </div>
      </section>
      <div id="analyze-body">
        <section class="chart-grid">
          <div class="chart-card reveal">
            <h3 class="chart-title">资源用量 · 容量</h3>
            <div id="usage-size-box" class="chart-box chart-box-gauge"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
          <div class="chart-card reveal">
            <h3 class="chart-title">资源用量 · 数量</h3>
            <div id="usage-count-box" class="chart-box chart-box-gauge"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal">
            <h3 class="chart-title">影像分类分布</h3>
            <div id="category-box" class="chart-box"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
        </section>
        <section class="chart-grid">
          <div class="chart-card reveal">
            <h3 class="chart-title">标签词云</h3>
            <div id="tag-box" class="chart-box"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
          <div class="chart-card reveal">
            <h3 class="chart-title">影像大小分布</h3>
            <div id="size-box" class="chart-box"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal">
            <h3 class="chart-title">
              上传行为趋势
              <span class="dimension-switch" id="dimension-switch">
                ${DIMENSIONS.map((item) => `
                  <button class="btn btn-sm ${item.value === current.timeDimension ? 'btn-primary' : ''}"
                          data-dim="${item.value}" type="button">${item.text}</button>
                `).join('')}
              </span>
            </h3>
            <div id="trend-box" class="chart-box chart-box-trend"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal" id="rank-card" hidden>
            <h3 class="chart-title">档案库使用排行（Top 10）</h3>
            <div id="rank-box" class="chart-box chart-box-trend"><div class="chart-empty" role="status">图表组件加载中…</div></div>
          </div>
        </section>
      </div>`;

    revealOnScroll(el);
    attachRippleAll(el);

    // 档案库切换：重新分析所选档案库
    $('#archive-select', el).addEventListener('change', (e) => {
      current.archiveId = e.target.value === '' ? null : e.target.value;
      currentArchiveName = e.target.options[e.target.selectedIndex]
        ? e.target.options[e.target.selectedIndex].textContent
        : '';
      loadAll(el, generation);
    });
    $('#export-report', el).addEventListener('click', () => exportAnalysis(el));

    // 趋势维度切换：只刷新折线图
    $('#dimension-switch', el).addEventListener('click', (e) => {
      const btn = e.target.closest('[data-dim]');
      if (!btn) {
        return;
      }
      current.timeDimension = btn.dataset.dim;
      $$('#dimension-switch .btn', el).forEach((item) => {
        item.classList.toggle('btn-primary', item === btn);
      });
      const scope = { archiveId: current.archiveId, timeDimension: current.timeDimension };
      loadChart(el, 'trend-box',
        () => loadChartLibraries().then(() => archiveAnalyzeApi.trend(scope)),
        trendOption,
        '还没有上传记录', generation);
    });

    loadArchiveOptions(el, query.archiveId, generation);

    // 窗口尺寸变化时让图表自适应，destroy 时移除
    window.addEventListener('resize', handleResize);
  },

  /** 让未完成的异步渲染失效，并释放实例与 resize 监听 */
  destroy() {
    viewGeneration += 1;
    requestGenerations.clear();
    chartData.clear();
    window.removeEventListener('resize', handleResize);
    destroyCharts();
  },
};
