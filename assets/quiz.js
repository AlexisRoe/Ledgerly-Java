// Usage: <div class="quiz" data-answer="b"><p>Question</p>
//   <button data-k="a" data-why="Feedback">text</button>... <div class="fb"></div></div>
document.querySelectorAll('.quiz').forEach(q=>{
  const fb=q.querySelector('.fb');
  q.querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>{
    const ok=b.dataset.k===q.dataset.answer;
    b.classList.add(ok?'right':'wrong');
    fb.textContent=(ok?'Correct. ':'Not quite. ')+b.dataset.why;
  }));
});
