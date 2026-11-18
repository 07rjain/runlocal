(() => {
  const demo = document.querySelector('.phone-demo');
  if (!demo) return;

  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const copy = demo.querySelector('.phone-reply-copy');
  const original = copy.firstElementChild;
  const stream = demo.querySelector('.phone-stream');
  const replay = demo.querySelector('.phone-replay');
  const words = original.textContent.trim().split(/\s+/);
  let observer;
  let timer;

  function finish() {
    window.clearTimeout(timer);
    copy.classList.remove('is-streaming');
    stream.hidden = true;
    stream.textContent = '';
  }

  function play() {
    observer?.disconnect();
    finish();
    if (motion.matches) return;

    stream.hidden = false;
    copy.classList.add('is-streaming');
    let count = 0;
    function next() {
      stream.textContent = words.slice(0, ++count).join(' ');
      timer = window.setTimeout(count === words.length ? finish : next, 85);
    }
    timer = window.setTimeout(next, 380);
  }

  replay.addEventListener('click', play);
  motion.addEventListener('change', () => {
    if (motion.matches) {
      observer?.disconnect();
      finish();
    }
    replay.hidden = motion.matches;
  });
  replay.hidden = motion.matches;

  if (!motion.matches && 'IntersectionObserver' in window) {
    observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) play();
    }, { threshold: 0.55 });
    observer.observe(demo.querySelector('.phone-frame'));
  }
})();
